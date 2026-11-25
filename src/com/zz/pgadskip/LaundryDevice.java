package com.zz.pgadskip;

/** 不把未识别的状态或缺失设备当作空闲。 */
final class LaundryDevice {
    static final int UNKNOWN = 0, IDLE = 10, BUSY = 20, FAULT = 30, DISABLED = 40;
    final String id, name, org;
    final int state;

    LaundryDevice(String id, String name, String org, int state) {
        this.id = id;
        this.name = name;
        this.org = org;
        this.state = normalize(state);
    }

    static int normalize(int state) {
        return state == IDLE || state == BUSY || state == FAULT || state == DISABLED ? state : UNKNOWN;
    }

    String label() {
        switch (state) {
            case IDLE: return "空闲";
            case BUSY: return "使用中";
            case FAULT: return "故障";
            case DISABLED: return "停用";
            default: return "未知";
        }
    }
}
