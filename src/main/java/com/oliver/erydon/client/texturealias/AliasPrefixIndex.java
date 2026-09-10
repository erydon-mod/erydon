package com.oliver.erydon.client.texturealias;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Immutable per-wrapper index. Retains source encounter order and exact directory boundaries. */
public final class AliasPrefixIndex {
    private final Map<String,List<Map.Entry<String,String>>> byPrefix;
    public AliasPrefixIndex(Map<String,String> aliases) {
        Objects.requireNonNull(aliases);
        Map<String,List<Map.Entry<String,String>>> build=new LinkedHashMap<>();
        for (Map.Entry<String,String> alias:aliases.entrySet()) {
            String path=Objects.requireNonNull(alias.getKey());
            if(path.isEmpty()) throw new IllegalArgumentException("Empty alias path");
            Map.Entry<String,String> entry=Map.entry(path,Objects.requireNonNull(alias.getValue()));
            add(build,"",entry);
            for (int slash=path.indexOf('/');slash>=0;slash=path.indexOf('/',slash+1)) {
                if(slash>0) add(build,path.substring(0,slash),entry);
            }
            add(build,path,entry);
        }
        Map<String,List<Map.Entry<String,String>>> frozen=new LinkedHashMap<>();
        build.forEach((key,value)->frozen.put(key,List.copyOf(value)));
        byPrefix=Collections.unmodifiableMap(frozen);
    }
    private static void add(Map<String,List<Map.Entry<String,String>>> out,String prefix,
                            Map.Entry<String,String> entry) {
        out.computeIfAbsent(prefix,ignored->new ArrayList<>()).add(entry);
    }
    public List<Map.Entry<String,String>> entries(String prefix) {
        return byPrefix.getOrDefault(Objects.requireNonNull(prefix),List.of());
    }
}
