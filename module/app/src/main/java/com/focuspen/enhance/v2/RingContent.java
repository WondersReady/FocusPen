package com.focuspen.enhance.v2;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Immutable, ordered application choices. Deleted entries stay excluded from automatic discovery. */
final class RingContent {
    static final String EMPTY = "v1";
    static final int MAX_ITEMS = 12, MAX_EXCLUDED = 256;
    static final String NOTES = "action:quick_note", CREATION = "action:quick_creation", SCREENSHOT = "action:partial_screenshot", RECOGNITION = "action:screen_recognition";
    static final String DEFAULT = EMPTY+"\n+"+NOTES+"\n+"+CREATION+"\n+"+SCREENSHOT;
    static boolean isAction(String id) { return NOTES.equals(id) || CREATION.equals(id) || SCREENSHOT.equals(id) || RECOGNITION.equals(id); }
    record Item(String id, boolean enabled) { }
    final List<Item> items;
    final Set<String> excluded;
    private RingContent(List<Item> items, Set<String> excluded) {
        this.items = List.copyOf(items); this.excluded = java.util.Collections.unmodifiableSet(new LinkedHashSet<>(excluded));
    }
    static boolean validId(String id) {
        return isAction(id) || id != null && id.length() <= 200 && id.matches("[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z][a-zA-Z0-9_]*)+");
    }
    static RingContent parse(String text) {
        if (text == null || text.length() > 65536) throw new IllegalArgumentException("Invalid content size");
        String[] lines = text.split("\n", -1);
        if (!EMPTY.equals(lines[0])) throw new IllegalArgumentException("Unknown content format");
        List<Item> items = new ArrayList<>(); Set<String> excluded = new LinkedHashSet<>(), seen = new LinkedHashSet<>();
        for (int i = 1; i < lines.length; i++) {
            String line = lines[i];
            if (line.length() < 2) throw new IllegalArgumentException("Empty content entry");
            String id = line.substring(1);
            if (!validId(id) || !seen.add(id)) throw new IllegalArgumentException("Invalid or duplicate application");
            switch (line.charAt(0)) {
                case '+' -> items.add(new Item(id, true));
                case '-' -> items.add(new Item(id, false));
                case '!' -> excluded.add(id);
                default -> throw new IllegalArgumentException("Unknown content state");
            }
        }
        if (items.size() > MAX_ITEMS || excluded.size() > MAX_EXCLUDED) throw new IllegalArgumentException("Too many content entries");
        return new RingContent(items, excluded);
    }
    String encode() {
        StringBuilder text = new StringBuilder(EMPTY);
        for (Item item : items) text.append('\n').append(item.enabled ? '+' : '-').append(item.id);
        for (String id : excluded) text.append('\n').append('!').append(id);
        return text.toString();
    }
    Item find(String id) { for (Item item : items) if (item.id.equals(id)) return item; return null; }
    RingContent add(String id) {
        if (!validId(id)) throw new IllegalArgumentException("Invalid application");
        if (find(id) != null) return this;
        if (items.size() >= MAX_ITEMS) throw new IllegalArgumentException("Ring is full");
        List<Item> next = new ArrayList<>(items); next.add(new Item(id, true));
        Set<String> removed = new LinkedHashSet<>(excluded); removed.remove(id);
        return new RingContent(next, removed);
    }
    RingContent remove(String id) {
        if (find(id) == null) return this;
        Set<String> removed = new LinkedHashSet<>(excluded); removed.add(id);
        if (removed.size() > MAX_EXCLUDED) throw new IllegalArgumentException("Too many exclusions");
        List<Item> next = new ArrayList<>(items); next.removeIf(item -> item.id.equals(id));
        return new RingContent(next, removed);
    }
    RingContent enable(String id, boolean enabled) {
        List<Item> next = new ArrayList<>();
        for (Item item : items) next.add(item.id.equals(id) ? new Item(id, enabled) : item);
        return new RingContent(next, excluded);
    }
    RingContent move(String id, int delta) {
        if (delta != -1 && delta != 1) throw new IllegalArgumentException("One position at a time");
        List<Item> next = new ArrayList<>(items);
        for (int i = 0; i < next.size(); i++) if (next.get(i).id.equals(id)) {
            int target = i + delta;
            if (target >= 0 && target < next.size()) java.util.Collections.swap(next, i, target);
            break;
        }
        return new RingContent(next, excluded);
    }
    RingContent position(String id, int position) {
        Item item=find(id);
        if(item==null || position<0 || position>=items.size())throw new IllegalArgumentException("Invalid menu position");
        List<Item> next=new ArrayList<>(items);next.remove(item);next.add(position,item);
        return new RingContent(next,excluded);
    }
    RingContent replace(String id,String replacement) {
        if(!validId(replacement)||find(id)==null)throw new IllegalArgumentException("Invalid replacement");
        if(id.equals(replacement))return this;
        if(find(replacement)!=null)throw new IllegalArgumentException("Item already exists");
        List<Item> next=new ArrayList<>();
        for(Item item:items)next.add(item.id.equals(id)?new Item(replacement,item.enabled):item);
        Set<String> removed=new LinkedHashSet<>(excluded);removed.remove(replacement);removed.add(id);
        if(removed.size()>MAX_EXCLUDED)throw new IllegalArgumentException("Too many exclusions");
        return new RingContent(next,removed);
    }
    RingContent discover(List<String> installedAllowlist) {
        RingContent next = this;
        for (String id : installedAllowlist)
            if (!excluded.contains(id) && next.items.size() < MAX_ITEMS) next = next.add(id);
        return next;
    }
}
