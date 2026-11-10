package emu.grasscutter.server.packet.recv;

import emu.grasscutter.Grasscutter;
import emu.grasscutter.game.entity.EntityAvatar;
import emu.grasscutter.game.player.Player;
import emu.grasscutter.game.quest.enums.QuestContent;
import emu.grasscutter.net.packet.*;
import emu.grasscutter.net.proto.EvtDoSkillSuccNotifyOuterClass.EvtDoSkillSuccNotify;
import emu.grasscutter.server.game.GameSession;

@Opcodes(PacketOpcodes.EvtDoSkillSuccNotify)
public class HandlerEvtDoSkillSuccNotify extends PacketHandler {

    @Override
    public void handle(GameSession session, byte[] header, byte[] payload) throws Exception {
        EvtDoSkillSuccNotify notify = EvtDoSkillSuccNotify.parseFrom(payload);

        var player = session.getPlayer();
        int skillId = notify.getSkillId();
        int casterId = notify.getCasterId();

        player.getAbilityManager().onSkillStart(session.getPlayer(), skillId, casterId);

        player.getStaminaManager().handleEvtDoSkillSuccNotify(session, skillId, casterId);
        player.getEnergyManager().handleEvtDoSkillSuccNotify(session, skillId, casterId);
        player.getQuestManager().queueEvent(QuestContent.QUEST_CONTENT_SKILL, skillId);

        // RL skill-probe: record last successful cast for the environment bridge.
        player.setLastSkillId(skillId);
        player.setLastSkillCasterId(casterId);
        var current = player.getTeamManager().getCurrentAvatarEntity();
        boolean self = current != null && casterId == current.getId();
        player.setLastSkillSelf(self);
        player.setLastSkillKind(classifySkill(player, skillId, casterId, self));
        player.setLastSkillTimeMs(System.currentTimeMillis());
    }

    /**
     * Classify a skillId against the caster avatar's depot.
     * Returns burst / attack / elemental_skill / attack_mode / other_entity / other.
     */
    private static String classifySkill(Player player, int skillId, int casterId, boolean self) {
        try {
            EntityAvatar caster;
            if (self) {
                caster = player.getTeamManager().getCurrentAvatarEntity();
            } else {
                var scene = player.getScene();
                var entity = scene != null ? scene.getEntityById(casterId) : null;
                if (!(entity instanceof EntityAvatar avatarEntity)) return "other_entity";
                caster = avatarEntity;
            }
            if (caster == null || caster.getAvatar() == null) return "other";
            var depot = caster.getAvatar().getSkillDepot();
            if (depot == null) return "other";
            if (depot.getEnergySkill() == skillId) return "burst";
            var skills = depot.getSkills();
            if (skills != null && !skills.isEmpty() && skills.get(0) == skillId) return "attack";
            if (skills != null && skills.size() >= 2 && skills.get(1) == skillId) return "elemental_skill";
            if (depot.getAttackModeSkill() == skillId) return "attack_mode";
        } catch (Throwable t) {
            Grasscutter.getLogger().warn("skill classify failed: {}", t.toString());
        }
        return "other";
    }
}
