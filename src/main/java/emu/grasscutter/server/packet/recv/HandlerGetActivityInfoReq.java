package emu.grasscutter.server.packet.recv;

import emu.grasscutter.net.packet.*;
import emu.grasscutter.net.proto.GetActivityInfoReqOuterClass;
import emu.grasscutter.server.game.GameSession;
import emu.grasscutter.server.packet.send.PacketGetActivityInfoRsp;
import java.util.HashSet;

@Opcodes(PacketOpcodes.GetActivityInfoReq)
public class HandlerGetActivityInfoReq extends PacketHandler {
    @Override
    public void handle(GameSession session, byte[] header, byte[] payload) throws Exception {
        var req = GetActivityInfoReqOuterClass.GetActivityInfoReq.parseFrom(payload);

        // activityManager may not be set yet if this packet races with login.
        var activityManager = session.getPlayer().getActivityManager();
        if (activityManager == null) return;

        session.send(
                new PacketGetActivityInfoRsp(
                        new HashSet<>(req.getActivityIdListList()), activityManager));
    }
}
