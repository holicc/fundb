package org.holicc.db;

import org.holicc.util.Wildcard;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

public class LocalDataBase implements DataBase {

    private final Map<String, DataEntry> entryMap = new HashMap<>();


    @Override
    public DataEntry persistInMemory(DataEntry entry) {
        return entryMap.put(entry.getKey(), entry);
    }

    @Override
    public DataEntry getEntry(String key) {
        if (!entryMap.containsKey(key)) return null;
        DataEntry entry = entryMap.get(key);
        LocalDateTime ttl = entry.getTtl();
        if (ttl == null || LocalDateTime.now().isBefore(ttl)) {
            return entry;
        } else {
            entryMap.remove(key);
            return null;
        }
    }

    @Override
    public void delEntry(String key) {
        entryMap.remove(key);
    }

    @Override
    public Set<String> keys(String pattern) {
        if (pattern == null || pattern.equals("")) return Set.of();
        Wildcard wildcard = Wildcard.compile(pattern);
        return entryMap.keySet().stream()
                .filter(wildcard::isMatch)
                .collect(Collectors.toSet());
    }


}
