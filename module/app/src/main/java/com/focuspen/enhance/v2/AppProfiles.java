package com.focuspen.enhance.v2;

import java.util.*;
import java.nio.charset.StandardCharsets;

/** Atomic application assignments and reusable templates; only package names and settings. */
final class AppProfiles {
    static final String EMPTY="v4", INHERIT="inherit";
    static final int FIELD_COUNT=25, PREVIEW_FIELD=6;
    // Snapshots cross Binder; keep the encoded profile value bounded.
    static final int MAX_SERIALIZED_LENGTH=262_144;
    static final String[] FIELDS={"button1Long","button1Double","button2Long","button2Double","up","down","preview",
            "button1Short","button2Short","proPinch","proDouble","proSlideUp","proSlideDown", "proTwoPinch","proTwoHold","proNormalUp","proNormalDown","proFourPinch","proFourHold","proMouseOne","proMouseHold","proMouseTwo","proMouseTwoHold","proMouseUp","proMouseDown"};
    record Profile(String pkg,boolean enabled,String mode,String template,List<String> values) {}
    record Template(String id,String name,List<String> values) {}
    final List<Profile> profiles;
    final List<Template> templates;
    private AppProfiles(List<Profile> p,List<Template> t) { profiles=List.copyOf(p); templates=List.copyOf(t); }
    static List<String> defaults() { return Collections.nCopies(FIELD_COUNT,INHERIT); }
    static List<String> options(int field) {
        if(field<0 || field>=FIELD_COUNT) throw new IllegalArgumentException("Invalid field");
        List<String> options=new ArrayList<>(); options.add(INHERIT);
        if(field>=13) { options.addAll(ProBindings.options(field-13)); } else if(field<4 || field>PREVIEW_FIELD) {
            for(PenAction action:PenAction.values()) if(action!=PenAction.NATIVE) options.add(action.id);
        } else if(field<PREVIEW_FIELD) {
            for(DirectionAdapter adapter:DirectionAdapter.values()) if(adapter!=DirectionAdapter.NATIVE) options.add(adapter.id);
        }
        else options.addAll(List.of("true","false"));
        return List.copyOf(options);
    }
    static AppProfiles parse(String text) {
        if(text==null||text.length()>MAX_SERIALIZED_LENGTH) throw new IllegalArgumentException("Invalid profiles");
        String[] lines=text.split("\n",-1); boolean legacy=lines[0].equals("v1");
        boolean old=lines[0].equals("v2"), v3=lines[0].equals("v3");
        if(!legacy&&!old&&!v3&&!lines[0].equals(EMPTY)) throw new IllegalArgumentException("Unknown profiles format");
        List<Profile> p=new ArrayList<>(); List<Template> t=new ArrayList<>(); Set<String> seen=new HashSet<>();
        for(int n=1;n<lines.length;n++) {
            String[] v=lines[n].split("\\|",-1);
            if(legacy) {
                if((v.length!=8&&v.length!=9)||!packageId(v[0])||!seen.add("a:"+v[0])||!flag(v[1])) throw new IllegalArgumentException("Invalid legacy profile");
                p.add(new Profile(v[0],v[1].equals("1"),"custom","",fields(v,2,v.length-2)));
            } else if((old?(v.length==9||v.length==10):v.length==3+(v3?13:FIELD_COUNT))&&v[0].equals("T")) {
                if(!templateId(v[1])||!seen.add("t:"+v[1])) throw new IllegalArgumentException("Invalid template ID");
                String name=new String(Base64.getUrlDecoder().decode(v[2]),StandardCharsets.UTF_8); validName(name);
                t.add(new Template(v[1],name,fields(v,3,v.length-3)));
            } else if((old?(v.length==11||v.length==12):v.length==5+(v3?13:FIELD_COUNT))&&v[0].equals("A")) {
                if(!packageId(v[1])||!seen.add("a:"+v[1])||!flag(v[2])||!List.of("default","custom","template").contains(v[3])) throw new IllegalArgumentException("Invalid application profile");
                if(v[3].equals("template")?!templateId(v[4]):!v[4].isEmpty()) throw new IllegalArgumentException("Invalid template reference");
                p.add(new Profile(v[1],v[2].equals("1"),v[3],v[4],fields(v,5,v.length-5)));
            } else throw new IllegalArgumentException("Invalid profile row");
        }
        if(t.size()>50) throw new IllegalArgumentException("Too many templates");
        AppProfiles result=new AppProfiles(p,t);
        for(Profile a:p) if(a.mode.equals("template")&&result.template(a.template)==null) throw new IllegalArgumentException("Missing template");
        return result;
    }
    private static boolean flag(String v) { return v.equals("1")||v.equals("0"); }
    private static boolean packageId(String p) { return RingContent.validId(p)&&!RingContent.isAction(p); }
    static boolean templateId(String id) { return id!=null&&id.matches("[a-zA-Z0-9_-]{1,64}"); }
    private static List<String> fields(String[] v,int offset,int count) {
        if(count!=6&&count!=7&&count!=13&&count!=FIELD_COUNT) throw new IllegalArgumentException("Invalid profile field count");
        ArrayList<String> f=new ArrayList<>(Arrays.asList(Arrays.copyOfRange(v,offset,offset+count)));
        while(f.size()<FIELD_COUNT) f.add(INHERIT);
        for(int i=0;i<FIELD_COUNT;i++) validate(i,f.get(i));
        return List.copyOf(f);
    }
    static void validate(int field,String value) {
        if(field<0||field>=FIELD_COUNT) throw new IllegalArgumentException("Invalid field");
        if(INHERIT.equals(value)) return;
        if(field>=13)ProBindings.validate(field-13,value); else if(field<4 || field>PREVIEW_FIELD) PenAction.parse(value);
        else if(field<6) DirectionAdapter.parse(value);
        else if(field==PREVIEW_FIELD && !List.of("true","false").contains(value)) throw new IllegalArgumentException("Invalid preview override");
    }
    static void validName(String name) { if(name==null||name.isBlank()||name.length()>40||name.chars().anyMatch(Character::isISOControl)) throw new IllegalArgumentException("Use a name of 1–40 characters"); }
    Profile find(String pkg) { for(Profile p:profiles) if(p.pkg.equals(pkg)) return p; return null; }
    Template template(String id) { for(Template t:templates) if(t.id.equals(id)) return t; return null; }
    List<String> effective(Profile p) { return p!=null&&p.mode.equals("template")?template(p.template).values:p==null||p.mode.equals("default")?defaults():p.values; }
    String value(String pkg,int field,String fallback) {
        Profile p=find(pkg); if(p==null||!p.enabled) return fallback;
        String v=effective(p).get(field); return INHERIT.equals(v)?fallback:v;
    }
    boolean enablesPreview() {
        for(Profile p:profiles) if(p.enabled && "true".equals(effective(p).get(PREVIEW_FIELD))) return true;
        return false;
    }
    private AppProfiles replace(Profile p) { List<Profile> next=new ArrayList<>(profiles); next.removeIf(a->a.pkg.equals(p.pkg)); next.add(p); return checked(next,templates); }
    private static AppProfiles checked(List<Profile> p,List<Template> t) { return parse(new AppProfiles(p,t).encode()); }
    AppProfiles set(String pkg,int field,String value) {
        Profile old=find(pkg);
        // A field edit copies the active mode; enabling/disabling must preserve saved custom values.
        List<String> f=new ArrayList<>(field>=0?effective(old):old==null?defaults():old.values);
        boolean enabled=old==null||old.enabled;
        String mode=old==null?"custom":old.mode, template=old==null?"":old.template;
        if(field>=0) { validate(field,value); f.set(field,value); mode="custom"; template=""; }
        else if(field==-2) { if(!List.of("true","false").contains(value)) throw new IllegalArgumentException("Invalid enabled state"); enabled=Boolean.parseBoolean(value); }
        return replace(new Profile(pkg,enabled,mode,template,List.copyOf(f)));
    }
    AppProfiles select(String pkg,String mode,String template) {
        Profile old=find(pkg); List<String> f=old==null?defaults():old.values;
        return replace(new Profile(pkg,old==null||old.enabled,mode,mode.equals("template")?template:"",f));
    }
    AppProfiles copy(String source,String target) {
        Profile from=find(source), to=find(target);
        if(from==null || source.equals(target) || !packageId(target)) throw new IllegalArgumentException("Invalid copy target");
        // Copy the selected overrides, preserving inheritance without linking future template edits.
        // Replacing an existing profile never changes whether the user enabled that target.
        return replace(new Profile(target,to==null?from.enabled:to.enabled,"custom","",effective(from)));
    }
    AppProfiles remove(String pkg) { List<Profile> next=new ArrayList<>(profiles); next.removeIf(p->p.pkg.equals(pkg)); return checked(next,templates); }
    AppProfiles saveTemplate(String id,String name,List<String> values) {
        validName(name); if(!templateId(id) || (values.size()!=6 && values.size()!=7 && values.size()!=13 && values.size()!=FIELD_COUNT)) throw new IllegalArgumentException("Invalid template values");
        ArrayList<String> normalized=new ArrayList<>(values);
        while(normalized.size()<FIELD_COUNT) normalized.add(INHERIT);
        for(int i=0;i<FIELD_COUNT;i++) validate(i,normalized.get(i));
        List<Template> next=new ArrayList<>(templates); next.removeIf(t->t.id.equals(id)); next.add(new Template(id,name,List.copyOf(normalized))); return checked(profiles,next);
    }
    AppProfiles renameTemplate(String id,String name) {
        Template t=template(id); if(t==null) throw new IllegalArgumentException("Missing template");
        return saveTemplate(id,name,t.values);
    }
    AppProfiles setTemplate(String id,int field,String value) {
        validate(field,value); Template t=template(id); if(t==null) throw new IllegalArgumentException("Missing template");
        List<String> next=new ArrayList<>(t.values); next.set(field,value); return saveTemplate(id,t.name,next);
    }
    AppProfiles removeTemplate(String id) {
        List<Template> next=new ArrayList<>(templates); next.removeIf(t->t.id.equals(id));
        List<Profile> apps=new ArrayList<>();
        for(Profile p:profiles) apps.add(p.mode.equals("template")&&p.template.equals(id)?new Profile(p.pkg,p.enabled,"default","",p.values):p);
        return checked(apps,next);
    }
    String encode() {
        StringBuilder b=new StringBuilder(EMPTY);
        for(Template t:templates) b.append("\nT|").append(t.id).append('|').append(Base64.getUrlEncoder().withoutPadding().encodeToString(t.name.getBytes(StandardCharsets.UTF_8))).append('|').append(String.join("|",t.values));
        for(Profile p:profiles) b.append("\nA|").append(p.pkg).append('|').append(p.enabled?1:0).append('|').append(p.mode).append('|').append(p.template).append('|').append(String.join("|",p.values));
        return b.toString();
    }
}
