package com.rush.rushaicodemother.orchestration.governance.audit;

/**
 * 受控操作的主体与调用边界。
 *
 * <p>不保存 Session、IP、User-Agent 或其他可变请求数据。</p>
 */
public record GenerationControlAuditSubject(
        ActorType actorType,
        Long actorUserId,
        Transport transport
) {

    public GenerationControlAuditSubject {
        if (actorType == null || transport == null) {
            throw new IllegalArgumentException("审计主体定义不完整");
        }
        if (actorType == ActorType.USER) {
            if (actorUserId == null || actorUserId <= 0) {
                throw new IllegalArgumentException("用户审计主体缺少有效编号");
            }
        } else if (actorUserId != null) {
            throw new IllegalArgumentException("非用户审计主体不得携带用户编号");
        }
    }

    public static GenerationControlAuditSubject httpUser(long userId) {
        return new GenerationControlAuditSubject(ActorType.USER, userId, Transport.HTTP);
    }

    public static GenerationControlAuditSubject anonymousHttp() {
        return new GenerationControlAuditSubject(ActorType.ANONYMOUS, null, Transport.HTTP);
    }

    public static GenerationControlAuditSubject internalSystem() {
        return new GenerationControlAuditSubject(ActorType.SYSTEM, null, Transport.INTERNAL);
    }

    public enum ActorType {
        USER,
        ANONYMOUS,
        SYSTEM
    }

    public enum Transport {
        HTTP,
        INTERNAL
    }
}
