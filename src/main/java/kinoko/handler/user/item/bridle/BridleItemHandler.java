package kinoko.handler.user.item.bridle;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import kinoko.handler.Handler;
import kinoko.packet.world.MessagePacket;
import kinoko.packet.world.WvsContext;
import kinoko.provider.ItemProvider;
import kinoko.provider.item.ItemInfo;
import kinoko.provider.item.ItemInfoType;
import kinoko.server.header.InHeader;
import kinoko.server.packet.InPacket;
import kinoko.world.field.Field;
import kinoko.world.field.mob.Mob;
import kinoko.world.field.mob.MobLeaveType;
import kinoko.world.item.InventoryOperation;
import kinoko.world.item.Item;
import kinoko.world.user.User;

public abstract class BridleItemHandler {
    protected static final Logger log = LogManager.getLogger(BridleItemHandler.class);

    @Handler(InHeader.UserBridleItemUseRequest)
    public static void handleUserBridleItemUseRequest(User user, InPacket inPacket) {
        log.error("handleUserBridleItemUseRequest not implemented yet: {}, {}", user, inPacket);

        // Not clear what update_time is.
        // Position is the position of the used bridle item in the user's USE inventory
        // The item ID is the ID of the item, e.g. 2270019 for "Net", "A net used for catching a Serpent that has been weakened. Double-click on it to catch a weakend Serpent."
        final int updateTime = inPacket.decodeInt(); // update_time (?)
        final int position = inPacket.decodeShort(); // nPOS
        final int itemId = inPacket.decodeInt(); // nItemID
        final int mobFieldId = inPacket.decodeInt(); // oid from !info, for example
        log.debug("Bridle Item Packet Details: updateTime: {}, position: {}, itemId: {}, mobFieldId: {}", updateTime, position, itemId, mobFieldId);

        // More info for "Net" in the .wz file: info.mob=1150002,  info.tradeBlock=1,  info.notSale=1,  info.price=1,  info.create=4032751,  info.left=-100,  info.right=100,  info.top=-100,  info.bottom=50,  info.mobHP=40
        // You can see the .wz file includes the mob ID & the mob HP, I assume that's the max HP for catching the mob? But that's pretty low. I'd say it's percentage.
        // So, we need to check the mob (maybe more detail in the packet?), and make sure the health is below 40%.
        // NOTE: when used near a different mob, the client doesn't send the server a UserBridleItemUseRequest, the client complains no tameable monster nearby.
        // *If it is, AND if the user has sufficient capacity, remove the item from user inventory, kill the mob (play some "catch!" animation), and add the caught mob item to user inventory.*
        // (Live Serpent: 4032751, which is what we get in info.create!)

        // Get Mob from Field, exit early if we can't get it
        final Field field = user.getField();
        final Optional<Mob> mobOpt = field.getMobPool().getById(mobFieldId);
        if (!mobOpt.isPresent()) {
            log.error("Mob supplied for bridle item use request could not be fetched!");
            user.dispose();
            return;
        }
        final Mob mob = mobOpt.get();
        log.info("Got mob: {}", mob);

        // Get Item WZ Data
        final Optional<ItemInfo> itemOpt = ItemProvider.getItemInfo(itemId);
        if (!itemOpt.isPresent()) {
            log.error("Item data not found for bridle item use request!");
            user.dispose();
            return;
        }
        final ItemInfo item = itemOpt.get();
        final Map<ItemInfoType, Object> itemInfos = item.getItemInfos();

        // get bridle item catch result item & HP threshold
        final int createItemId = (int) itemInfos.get(ItemInfoType.create);
        final int defaultMaxHPThresholdPercent = 15;
        final int maxHPForCatch = (int) itemInfos.getOrDefault(ItemInfoType.mobHP, defaultMaxHPThresholdPercent);

        // Get create item data
        final Optional<ItemInfo> createItemOpt = ItemProvider.getItemInfo(createItemId);
        if (!createItemOpt.isPresent()) {
            log.error("Create item data not found for bridle item use request!");
            user.dispose();
            return;
        }
        final ItemInfo createItemInfo = createItemOpt.get();

        // A nice check would be to validate mob's proximity:
        //  Client already only sends the request when the valid mob is nearby, but could be spoofed.
        //  There's also left/right/top/bottom in the item's wz data, maybe that should be the range of the item,
        //  in which case you'd want to only check mobs within the given range.

        // Validate mob from field has HP below mob HP threshold
        final int mobMaxHP = mob.getMaxHp();
        // TODO: fix this calculation
        final boolean mobHealthLowEnough = (int) ((mob.getHp() / mobMaxHP) * 100) < maxHPForCatch;
        if (!mobHealthLowEnough) {
            user.write(MessagePacket.system("Lower your prey's HP to successfully capture!"));
            user.dispose();
            return;
        }

        // Validate user has sufficient capacity (ETC)
        final boolean canAddCatchItem = user.getInventoryManager().canAddItem(createItemId, 1);
        if (!canAddCatchItem) {
            user.write(MessagePacket.system("Insufficient ETC space to capture your prey!"));
            user.dispose();
            return;
        }

        // TODO: implement cool "catch!" animation, replace mob death animation
        // Kill the mob for now, instead of a clean removal + "Catch!" animation, not sure how to do that yet.
        field.getMobPool().removeMob(mob, MobLeaveType.ETC);

        // Add the etc item
        final Item createdItem = createItemInfo.createItem(user.getNextItemSn(), 1);
        final Optional<List<InventoryOperation>> addItemResult = user.getInventoryManager().addItem(createdItem);
        if (addItemResult.isEmpty()) {
            log.error("Failed to add item to inventory");
            user.dispose();
            return;
        }
        user.write(WvsContext.inventoryOperation(addItemResult.get(), true));

        // Remove the use item
        final Optional<List<InventoryOperation>> removeItemResult = user.getInventoryManager().removeItem(itemId, 1);
        if (addItemResult.isEmpty()) {
            log.error("Failed to remove item from inventory");
            user.dispose();
            return;
        }
        user.write(WvsContext.inventoryOperation(removeItemResult.get(), false));

        // This will "unstick" the user, otherwise the user cannot perform any more user handler actions
        user.dispose();
    }

    public static void tryDecodeFourMoreBytes(InPacket inPacket) {
        try {
            final byte anotherByte1 = inPacket.decodeByte();
            log.info("anotherByte1: {}", anotherByte1);
            final byte anotherByte2 = inPacket.decodeByte();
            log.info("anotherByte2, enough for a short: {}", anotherByte2);
            final byte anotherByte3 = inPacket.decodeByte();
            log.info("anotherByte3: {}", anotherByte3);
            final byte anotherByte4 = inPacket.decodeByte();
            log.info("anotherByte4, enough for an int. You'd need 8 for a long: {}", anotherByte4);
        } catch (Exception e) {
            log.error("Exception caught while decoding 4 more bytes:", e);
        }
    }
}
