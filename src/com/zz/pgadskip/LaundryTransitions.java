package com.zz.pgadskip;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 首次成功查询只建立基线；只有明确的使用中→空闲才提醒，同一空闲状态不重复提醒。 */
final class LaundryTransitions {
    private final Map<String, Integer> known = new HashMap<>();

    List<LaundryDevice> update(List<LaundryDevice> devices, Set<String> selected) {
        known.keySet().retainAll(selected);
        List<LaundryDevice> alerts = new ArrayList<>();
        for (LaundryDevice d : devices) {
            if (!selected.contains(d.id) || d.state == LaundryDevice.UNKNOWN) continue;
            Integer previous = known.put(d.id, d.state);
            if (previous != null && previous == LaundryDevice.BUSY && d.state == LaundryDevice.IDLE) alerts.add(d);
        }
        return alerts;
    }
}
