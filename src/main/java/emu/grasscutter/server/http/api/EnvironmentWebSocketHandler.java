package emu.grasscutter.server.http.api;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import emu.grasscutter.Grasscutter;
import emu.grasscutter.game.entity.EntityAvatar;
import emu.grasscutter.game.entity.EntityGadget;
import emu.grasscutter.game.entity.GameEntity;
import emu.grasscutter.game.inventory.GameItem;
import emu.grasscutter.game.inventory.ItemType;
import emu.grasscutter.game.player.Player;
import emu.grasscutter.game.props.FightProperty;
import emu.grasscutter.game.world.Position;
import emu.grasscutter.net.proto.MotionStateOuterClass.MotionState;
import emu.grasscutter.utils.JsonUtils;
import io.javalin.Javalin;
import io.javalin.websocket.WsContext;
import io.javalin.websocket.WsMessageContext;
import java.net.InetSocketAddress;
import java.util.Map;

/** Local-only Gym-style observation bridge over the existing HTTP server. */
public final class EnvironmentWebSocketHandler {
    private EnvironmentWebSocketHandler() {}

    public static void configure(Javalin javalin) {
        javalin.ws("/api/env/ws", ws -> {
            ws.onConnect(ctx -> {
                var remote = (InetSocketAddress) ctx.session.getRemoteAddress();
                if (!isLoopback(remote.getAddress().getHostAddress())) {
                    ctx.closeSession(1008, "environment endpoint is local-only");
                }
            });
            ws.onMessage(EnvironmentWebSocketHandler::onMessage);
        });
    }

    private static boolean isLoopback(String address) {
        return "127.0.0.1".equals(address) || "::1".equals(address)
                || "0:0:0:0:0:0:0:1".equals(address);
    }

    private static void onMessage(WsMessageContext ctx) {
        try {
            JsonObject request = JsonParser.parseString(ctx.message()).getAsJsonObject();
            String type = request.has("type") ? request.get("type").getAsString() : "observe";
            if (!type.equals("observe") && !type.equals("reset") && !type.equals("step")
                    && !type.equals("inventory") && !type.equals("entities")
                    && !type.equals("team")) {
                send(ctx, Map.of("type", "error", "code", "unsupported_action"));
                return;
            }
            String uidValue = request.has("uid") ? request.get("uid").getAsString() : ctx.queryParam("uid");
            if (uidValue == null || uidValue.isBlank()) throw new IllegalArgumentException("uid is required");
            int uid = Integer.parseInt(uidValue);
            Player player = Grasscutter.getGameServer() == null
                    ? null : Grasscutter.getGameServer().getPlayerByUid(uid);
            if (player == null) {
                send(ctx, Map.of("type", "error", "code", "player_not_found", "uid", uid));
                return;
            }
            if (type.equals("inventory")) {
                var inventory = new java.util.LinkedHashMap<String, Object>();
                inventory.put("type", "inventory_result");
                if (request.has("requestId")) inventory.put("requestId", request.get("requestId"));
                inventory.put("items", materialCounts(player));
                send(ctx, inventory);
                return;
            }
            if (type.equals("entities")) {
                var entities = new java.util.LinkedHashMap<String, Object>();
                entities.put("type", "entities_result");
                if (request.has("requestId")) entities.put("requestId", request.get("requestId"));
                double radius = request.has("radius") ? request.get("radius").getAsDouble() : 60.0;
                entities.put("items", nearbyEntities(player, radius));
                send(ctx, entities);
                return;
            }
            if (type.equals("team")) {
                var result = new java.util.LinkedHashMap<String, Object>();
                result.put("type", "team_result");
                if (request.has("requestId")) result.put("requestId", request.get("requestId"));
                result.put("items", teamInfo(player));
                send(ctx, result);
                return;
            }
            if (type.equals("step")) applyAction(player, request);
            var result = new java.util.LinkedHashMap<String, Object>();
            result.put("type", type + "_result");
            if (request.has("requestId")) result.put("requestId", request.get("requestId"));
            result.put("observation", observation(player));
            result.put("reward", 0.0f);
            result.put("terminated", !player.getTeamManager().getCurrentAvatarEntity().isAlive());
            result.put("truncated", false);
            send(ctx, result);
        } catch (Exception e) {
            send(ctx, Map.of("type", "error", "code", "invalid_request",
                    "detail", String.valueOf(e.getMessage())));
        }
    }

    private static void applyAction(Player player, JsonObject request) {
        if (!request.has("action") || !request.get("action").isJsonObject()) return;
        JsonObject action = request.getAsJsonObject("action");
        String name = action.has("type") ? action.get("type").getAsString() : "noop";
        switch (name) {
            case "noop" -> { }
            case "revive" -> {
                emu.grasscutter.game.ability.PartyReviveHelper.reviveAll(player, 1.0f);
            }
            case "set_health" -> {
                float health = action.get("health").getAsFloat();
                var avatar = player.getTeamManager().getCurrentAvatarEntity();
                float max = avatar.getFightProperty(FightProperty.FIGHT_PROP_MAX_HP);
                avatar.setFightProperty(FightProperty.FIGHT_PROP_CUR_HP,
                        Math.max(0f, Math.min(max, health)));
                player.sendPacket(new emu.grasscutter.server.packet.send.PacketAvatarFightPropUpdateNotify(
                        avatar.getAvatar(), FightProperty.FIGHT_PROP_CUR_HP));
            }
            case "switch_avatar" -> {
                if (!action.has("avatar_id"))
                    throw new IllegalArgumentException("switch_avatar requires avatar_id");
                int avatarId = action.get("avatar_id").getAsInt();
                boolean switched = false;
                for (EntityAvatar entity : player.getTeamManager().getActiveTeam()) {
                    if (entity != null && entity.getAvatar() != null
                            && entity.getAvatar().getAvatarId() == avatarId
                            && entity.isAlive()) {
                        player.getTeamManager().changeAvatar(entity.getAvatar().getGuid());
                        switched = true;
                        break;
                    }
                }
                if (!switched)
                    throw new IllegalArgumentException(
                            "avatar not in active team or dead: " + avatarId);
            }
            case "teleport" -> {
                if (!action.has("x") || !action.has("y") || !action.has("z"))
                    throw new IllegalArgumentException("teleport requires x, y, z");
                var pos = new Position(
                        action.get("x").getAsFloat(),
                        action.get("y").getAsFloat(),
                        action.get("z").getAsFloat());
                player.getWorld().transferPlayerToScene(player, player.getSceneId(), pos);
            }
            default -> throw new IllegalArgumentException("unsupported action: " + name);
        }
    }

    private static Map<String, Object> observation(Player player) {
        Position p = player.getPosition();
        Position rotation = player.getRotation();
        EntityAvatar avatar = player.getTeamManager().getCurrentAvatarEntity();
        var result = new java.util.LinkedHashMap<String, Object>();
        result.put("uid", player.getUid());
        result.put("sceneId", player.getSceneId());
        result.put("position", Map.of("x", p.getX(), "y", p.getY(), "z", p.getZ()));
        if (rotation != null) {
            result.put("rotation", Map.of(
                    "x", rotation.getX(), "y", rotation.getY(), "z", rotation.getZ()));
        }
        result.put("hp", avatar.getFightProperty(FightProperty.FIGHT_PROP_CUR_HP));
        result.put("maxHp", avatar.getFightProperty(FightProperty.FIGHT_PROP_MAX_HP));
        result.put("lifeState", avatar.getLifeState().name());
        MotionState motionState = avatar.getMotionState();
        if (motionState != null) {
            result.put("motionState", motionState.name());
            result.put("motionStateValue", motionState.getNumber());
        }
        // Last successful skill cast (EvtDoSkillSuccNotify), for key-probe isolation.
        // lastSkillKind: burst / attack / elemental_skill / attack_mode / other_entity / other.
        result.put("lastSkillId", player.getLastSkillId());
        result.put("lastSkillCasterId", player.getLastSkillCasterId());
        result.put("lastSkillKind", player.getLastSkillKind());
        result.put("lastSkillTimeMs", player.getLastSkillTimeMs());
        result.put("lastSkillSelf", player.isLastSkillSelf());
        result.put("currentAvatarEntityId", avatar != null ? avatar.getId() : 0);
        return result;
    }

    /** Interactive gadgets (gather objects, chests, …) near the player. */
    private static java.util.List<Map<String, Object>> nearbyEntities(
            Player player, double radius) {
        var items = new java.util.ArrayList<Map<String, Object>>();
        var scene = player.getScene();
        Position origin = player.getPosition();
        if (scene == null) return items;
        for (GameEntity entity : scene.getEntities().values()) {
            if (!(entity instanceof EntityGadget gadget)) continue;
            Position pos = gadget.getPosition();
            double distance = Math.sqrt(
                    Math.pow(pos.getX() - origin.getX(), 2)
                    + Math.pow(pos.getY() - origin.getY(), 2)
                    + Math.pow(pos.getZ() - origin.getZ(), 2));
            if (distance > radius) continue;
            var row = new java.util.LinkedHashMap<String, Object>();
            row.put("entityId", gadget.getId());
            row.put("gadgetId", gadget.getGadgetId());
            var data = gadget.getGadgetData();
            if (data != null) {
                row.put("type", data.getType() == null ? "" : data.getType().name());
                row.put("jsonName", data.getJsonName());
                row.put("itemJsonName", data.getItemJsonName());
            }
            row.put("x", pos.getX());
            row.put("y", pos.getY());
            row.put("z", pos.getZ());
            row.put("distance", distance);
            items.add(row);
        }
        items.sort(java.util.Comparator.comparingDouble(
                row -> ((Number) row.get("distance")).doubleValue()));
        return items;
    }

    /** Active team members, used by switch_avatar / GM automation. */
    private static java.util.List<Map<String, Object>> teamInfo(Player player) {
        var items = new java.util.ArrayList<Map<String, Object>>();
        var team = player.getTeamManager().getActiveTeam();
        int current = player.getTeamManager().getCurrentCharacterIndex();
        for (int index = 0; index < team.size(); index++) {
            EntityAvatar entity = team.get(index);
            if (entity == null || entity.getAvatar() == null) continue;
            var row = new java.util.LinkedHashMap<String, Object>();
            row.put("index", index);
            row.put("avatarId", entity.getAvatar().getAvatarId());
            row.put("guid", entity.getAvatar().getGuid());
            row.put("alive", entity.isAlive());
            row.put("current", index == current);
            items.add(row);
        }
        return items;
    }

    /** All ITEM_MATERIAL counts, keyed by item id string; used for harvest deltas. */
    private static Map<String, Object> materialCounts(Player player) {
        var items = new java.util.TreeMap<String, Object>();
        for (GameItem item : player.getInventory().getItems().values()) {
            var data = item.getItemData();
            if (data != null && data.getItemType() == ItemType.ITEM_MATERIAL) {
                items.put(String.valueOf(item.getItemId()), item.getCount());
            }
        }
        return items;
    }

    private static void send(WsContext ctx, Object value) {
        ctx.send(JsonUtils.encode(value));
    }
}
