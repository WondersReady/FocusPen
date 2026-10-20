package com.focuspen.enhance.v2;

/** Boundary and malformed-IPC inputs, independent of Android and host preferences. */
public final class ConfigSpecTest {
    public static void main(String[] args) {
        ConfigSpec.validate("longPressMs", 150);
        ConfigSpec.validate("longPressMs", 800);
        reject("longPressMs", 149); reject("longPressMs", 801);
        reject("longPressMs", "200"); reject("enabled", 1);
        reject("enabled", "true"); reject("confirmMode", "unknown");
        reject("schemaVersion", 100); reject("revision", 0);
        reject("enabled", null); reject(null, true);
        ConfigSpec.validate("button1Long", "native");
        reject("button3Short", "ring"); ConfigSpec.validate("button1Short","note"); ConfigSpec.validate("button2Short","up");
        for(String key:java.util.List.of("button1Short","button2Short"))
            if(!"none".equals(ConfigSpec.DEFAULTS.get(key)))throw new AssertionError("Short press must default to none");
        for(String key:ConfigSpec.PRO_GESTURES){ConfigSpec.validate(key,"ring");reject(key,"bogus");}
        ConfigSpec.validate("button3Short","native"); ConfigSpec.validate("button1Double","note");
        reject("doublePressMs",179); reject("doublePressMs",451);
        reject("button4Short", "ring"); reject("button2Long", "unknown");
        ConfigSpec.validate("ringMaterial", "texture"); ConfigSpec.validate("ringMaterial", "glass");
        reject("ringMaterial", "unknown"); reject("ringMaterial", true);
        if (!Boolean.TRUE.equals(ConfigSpec.DEFAULTS.get("enabled"))) throw new AssertionError("Fresh installs must be active; LSPosed controls module lifetime");
        if (!Boolean.FALSE.equals(ConfigSpec.DEFAULTS.get("writingAudioEnabled"))) throw new AssertionError("Writing audio must default off");
        if (ConfigSpec.DEFAULTS.containsKey("confirmMode") || ConfigSpec.DEFAULTS.containsKey("reduceMotion")
                || ConfigSpec.DEFAULTS.containsKey("lowCost")) throw new AssertionError("Legacy UI controls remain exposed");
        contentAndProfiles();
        profileModesAndOptions();
        profileTogglePreservesCustomValues();
        profileCopyAndRestore();
        reverseDirectionProfiles();
        expandedProfiles();
        System.out.println("Config boundaries, fixed buttons, menu content and application inheritance: PASS");
    }
    private static void expandedProfiles() {
        AppProfiles p=AppProfiles.parse("v2\nT|old|T2xk|note|none|none|none|swipe|page|true\nA|com.example.old|1|template|old|none|none|none|none|native|native|false");
        if(!p.value("com.example.old",0,"none").equals("note")||!p.value("com.example.old",6,"false").equals("true"))
            throw new AssertionError("Legacy template altered");
        for(int field=7;field<AppProfiles.FIELD_COUNT;field++){
            if(!p.value("com.example.old",field,"ring").equals("ring"))throw new AssertionError("New fields did not inherit");
            p=p.setTemplate("old",field,field>=13?"volumeUp":"screenshot");
        }
        p=AppProfiles.parse(p.encode()).copy("com.example.old","com.example.copy");
        for(int field=7;field<AppProfiles.FIELD_COUNT;field++){
            if(!p.value("com.example.copy",field,"none").equals(field>=13?"volumeUp":"screenshot"))throw new AssertionError("Expanded field lost on copy");
            p=p.set("com.example.copy",field,"none");
            if(!p.value("com.example.copy",field,"ring").equals("none"))throw new AssertionError("Explicit none did not override");
        }
        if(!p.value("com.example.old",9,"none").equals("screenshot"))throw new AssertionError("Copy edited source template");
    }
    private static void reverseDirectionProfiles() {
        for(String kind:java.util.List.of("page","dpad","scroll","swipe")) {
            String reversed=kind+"_reverse";
            AppProfiles p=AppProfiles.parse(AppProfiles.EMPTY).set("com.example.reverse",4,reversed)
                    .saveTemplate("reverse","反向",AppProfiles.defaults()).setTemplate("reverse",5,reversed)
                    .select("com.example.template","template","reverse");
            p=AppProfiles.parse(p.encode());
            if(!p.value("com.example.reverse",4,"native").equals(reversed)
                    || !p.value("com.example.reverse",5,"native").equals("native")
                    || !p.value("com.example.template",5,"native").equals(reversed))
                throw new AssertionError("Reverse fields did not persist independently");
            p=p.copy("com.example.template","com.example.copy");
            if(!p.value("com.example.copy",5,"native").equals(reversed)) throw new AssertionError("Reverse lost when copying template");
        }
        for(String bad:java.util.List.of("native_reverse","none_reverse","swipe_reverse_reverse","reverse","page_reverseX")) {
            try { AppProfiles.validate(4,bad); throw new AssertionError("Invalid reverse direction accepted"); }
            catch(IllegalArgumentException expected) { }
        }
    }
    private static void profileCopyAndRestore() {
        String source="com.example.source", target="com.example.target", fresh="com.example.fresh";
        AppProfiles p=AppProfiles.parse(AppProfiles.EMPTY).set(source,0,"note").set(source,6,"true")
                .saveTemplate("reading","阅读",AppProfiles.defaults()).setTemplate("reading",4,"swipe")
                .setTemplate("reading",6,"false").select(source,"template","reading").set(source,-2,"false")
                .set(target,0,"creation");
        AppProfiles.Profile originalSource=p.find(source);
        java.util.List<String> expected=p.effective(originalSource);
        p=AppProfiles.parse(p.copy(source,target).copy(source,fresh).encode());
        if(!p.find(source).equals(originalSource) || !p.find(target).enabled() || p.find(fresh).enabled())
            throw new AssertionError("Copy changed source or target enabled state");
        if(!p.find(target).mode().equals("custom") || !p.find(target).template().isEmpty()
                || !p.find(target).values().equals(expected) || !p.find(fresh).values().equals(expected))
            throw new AssertionError("Copy did not retain effective overrides");
        if(!p.value(target,0,"ring").equals("ring")) throw new AssertionError("Copy froze inherited values");
        p=p.setTemplate("reading",4,"page");
        if(!p.value(target,4,"native").equals("swipe")) throw new AssertionError("Copy still follows source template");
        p=p.set(target,-2,"false").select(target,"default","");
        if(p.find(target).enabled() || !p.find(target).values().equals(expected)
                || !p.effective(p.find(target)).equals(AppProfiles.defaults()))
            throw new AssertionError("Restore-default mode lost overrides or enabled state");
        p=p.set(target,-2,"true").select(target,"custom","");
        if(!p.value(target,4,"native").equals("swipe")) throw new AssertionError("Saved copy did not survive restore");
        p=p.select(source,"default","").copy(source,target);
        if(!p.find(target).values().equals(AppProfiles.defaults())) throw new AssertionError("Copied hidden custom values from default mode");
        for(String invalid:java.util.List.of(source,RingContent.NOTES,"bad")) {
            try { p.copy(source,invalid); throw new AssertionError("Invalid copy accepted"); }
            catch(IllegalArgumentException expectedFailure) { }
        }
        try { p.copy("com.example.missing",target); throw new AssertionError("Missing source accepted"); }
        catch(IllegalArgumentException expectedFailure) { }
        AppProfiles full=AppProfiles.parse(AppProfiles.EMPTY);
        for(int i=0;i<100;i++) full=full.set("com.example.app"+i,0,"note");
        full=full.copy("com.example.app0","com.example.app99");
        if(full.profiles.size()!=100) throw new AssertionError("Replacing at capacity changed profile count");
        try { full.copy("com.example.app0",fresh); throw new AssertionError("Copy exceeded application capacity"); }
        catch(IllegalArgumentException expectedFailure) { }
    }
    private static void profileTogglePreservesCustomValues() {
        String pkg="com.example.saved";
        AppProfiles original=AppProfiles.parse(AppProfiles.EMPTY)
                .set(pkg,0,"note").set(pkg,4,"swipe").set(pkg,6,"true")
                .saveTemplate("reader","阅读",AppProfiles.defaults())
                .setTemplate("reader",0,"creation").setTemplate("reader",4,"page").setTemplate("reader",6,"false");
        java.util.List<String> saved=original.find(pkg).values();
        for(String mode:java.util.List.of("default","template","custom")) {
            AppProfiles p=original.select(pkg,mode,mode.equals("template")?"reader":"");
            java.util.List<String> effective=p.effective(p.find(pkg));
            for(String enabled:java.util.List.of("false","true")) {
                p=AppProfiles.parse(p.set(pkg,-2,enabled).encode());
                if(!p.find(pkg).values().equals(saved))
                    throw new AssertionError("Toggling " + mode + " overwrote saved custom fields");
                if(!p.find(pkg).mode().equals(mode) || !p.effective(p.find(pkg)).equals(effective))
                    throw new AssertionError("Toggling changed the selected mode or effective fields");
                if(p.find(pkg).enabled()!=Boolean.parseBoolean(enabled)) throw new AssertionError("Toggle not applied");
            }
            p=p.set(pkg,-1,AppProfiles.INHERIT);
            if(!p.find(pkg).values().equals(saved)) throw new AssertionError("Ensuring an existing profile discarded saved fields");
            p=p.select(pkg,"custom","");
            if(!p.value(pkg,0,"ring").equals("note") || !p.value(pkg,4,"native").equals("swipe")
                    || !p.value(pkg,6,"false").equals("true"))
                throw new AssertionError("Custom settings were not restored after toggle");
        }
    }
    private static void profileModesAndOptions() {
        AppProfiles p=AppProfiles.parse(AppProfiles.EMPTY);
        p=p.set("com.example.preview",AppProfiles.PREVIEW_FIELD,"true");
        if(!p.value("com.example.preview",6,"false").equals("true")) throw new AssertionError("Preview-first profile failed");
        p=p.set("com.example.preview",4,"dpad").select("com.example.preview","default","");
        if(!p.value("com.example.preview",6,"false").equals("false") || p.enablesPreview())
            throw new AssertionError("Default mode must inherit all seven fields");
        p=p.select("com.example.preview","custom","");
        if(!p.value("com.example.preview",6,"false").equals("true") || !p.value("com.example.preview",4,"native").equals("dpad"))
            throw new AssertionError("Mode switch lost saved custom values");
        p=p.saveTemplate("sketch","绘画",AppProfiles.defaults()).setTemplate("sketch",6,"false")
                .setTemplate("sketch",0,"creation").select("com.example.preview","template","sketch");
        if(!p.value("com.example.preview",0,"ring").equals("creation") || !p.value("com.example.preview",6,"true").equals("false"))
            throw new AssertionError("Template effective fields failed");
        p=p.setTemplate("sketch",0,"note");
        if(!p.value("com.example.preview",0,"ring").equals("note")) throw new AssertionError("Template edits did not propagate");
        p=p.set("com.example.preview",4,"page");
        if(!p.find("com.example.preview").mode().equals("custom") || !p.value("com.example.preview",0,"ring").equals("note"))
            throw new AssertionError("Editing template assignment must copy effective values");
        p=p.set("com.example.preview",-2,"false").select("com.example.preview","template","sketch");
        if(p.find("com.example.preview").enabled()) throw new AssertionError("Choosing a mode re-enabled a disabled profile");
        p=p.removeTemplate("sketch").select("com.example.preview","custom","").set("com.example.preview",-2,"true");
        if(!p.value("com.example.preview",4,"native").equals("page")) throw new AssertionError("Deleting template discarded saved custom fields");
        for(int f=0;f<AppProfiles.FIELD_COUNT;f++) {
            java.util.List<String> options=AppProfiles.options(f);
            if(new java.util.HashSet<>(options).size()!=options.size() || options.contains("native"))
                throw new AssertionError("Duplicate or legacy choice exposed");
            for(String option:options) AppProfiles.validate(f,option);
            if(f>=4 && f<6 && !options.equals(java.util.List.of("inherit","page","page_reverse","dpad","dpad_reverse","scroll","scroll_reverse","swipe","swipe_reverse","none")))
                throw new AssertionError("Direction options missing");
            if(f==6 && !options.equals(java.util.List.of("inherit","true","false")))
                throw new AssertionError("Preview options missing");
            if(f<4 && !options.contains("ring")) throw new AssertionError("Key actions missing");
        }
    }
    private static void contentAndProfiles() {
        RingContent ordering=RingContent.parse(RingContent.DEFAULT).enable(RingContent.CREATION,false);
        ordering=ordering.position(RingContent.SCREENSHOT,0).replace(RingContent.CREATION,RingContent.RECOGNITION);
        if(!ordering.items.get(0).id().equals(RingContent.SCREENSHOT)||ordering.find(RingContent.RECOGNITION).enabled()
                ||!ordering.excluded.contains(RingContent.CREATION))throw new AssertionError("Menu reorder or replacement lost state");
        try{ordering.replace(RingContent.NOTES,RingContent.RECOGNITION);throw new AssertionError("Duplicate replacement accepted");}catch(IllegalArgumentException expected){}
        if(!RingContent.parse(ordering.encode()).encode().equals(ordering.encode()))throw new AssertionError("Menu edits failed roundtrip");
        RingContent c=RingContent.parse("v1").discover(java.util.List.of("com.miui.notes","com.miui.creation"));
        c=c.add(RingContent.NOTES).add(RingContent.CREATION).add(RingContent.SCREENSHOT).add("com.example.app");
        if(c.items.size()!=6) throw new AssertionError("Mixed actions count toward capacity");
        for (int i=0; i<6; i++) c=c.add("com.example.extra"+i);
        if(c.items.size()!=12) throw new AssertionError("Twelve item capacity failed");
        try { c.add("com.example.thirteenth"); throw new AssertionError("Accepted thirteenth item"); } catch(IllegalArgumentException expected) {}
        c=c.move(RingContent.SCREENSHOT,-1).enable(RingContent.CREATION,false).remove("com.miui.notes");
        String saved=c.encode();
        if(!RingContent.parse(saved).discover(java.util.List.of("com.miui.notes")).encode().equals(saved)) throw new AssertionError("Deleted auto item reappeared");
        if(c.find(RingContent.CREATION).enabled()) throw new AssertionError("Disabled state lost");
        reject(ConfigSpec.CONTENT,"v1\n+action:unknown"); reject(ConfigSpec.CONTENT,"v1\n+com.example.app\n!com.example.app");
        AppProfiles p=AppProfiles.parse("v1").set("com.example.app",0,"note").set("com.example.app",4,"page").set("com.example.app",6,"true");
        p=AppProfiles.parse(p.encode());
        if(!p.value("com.example.app",0,"ring").equals("note") || !p.value("com.example.app",1,"media").equals("media")
                || !p.value("com.other.app",0,"ring").equals("ring")
                || !p.value("com.example.app",6,"false").equals("true")
                || !p.enablesPreview()) throw new AssertionError("Per-field fallback failed");
        p=p.saveTemplate("reading","阅读应用",AppProfiles.defaults());
        if(p.template("reading")==null || p.template("reading").values().size()!=AppProfiles.FIELD_COUNT)
            throw new AssertionError("Template normalization failed");
        p=p.setTemplate("reading",4,"page").set("com.example.app",-1,AppProfiles.INHERIT);
        p=p.select("com.example.app","template","reading");
        if(!p.value("com.example.app",4,"native").equals("page")) throw new AssertionError("Template selection failed");
        p=p.renameTemplate("reading","阅读应用新版");
        if(!p.template("reading").name().equals("阅读应用新版")) throw new AssertionError("Template rename failed");
        p=p.removeTemplate("reading");
        if(p.template("reading")!=null || !p.value("com.example.app",4,"native").equals("native"))
            throw new AssertionError("Template removal fallback failed");
        AppProfiles oldV2=AppProfiles.parse("v2\nA|com.example.old|1|custom||note|none|none|none|native|native");
        if(!oldV2.value("com.example.old",6,"false").equals("false")) throw new AssertionError("Old profile format lost preview inheritance");
        if(!p.set("com.example.app",-2,"false").value("com.example.app",0,"ring").equals("ring")) throw new AssertionError("Disabled profile applied");
        if(p.remove("com.example.app").find("com.example.app")!=null) throw new AssertionError("Delete failed");
        AppProfiles pointer=AppProfiles.parse("v2").set("com.example.app",4,"swipe").set("com.example.app",5,"scroll");
        if(!AppProfiles.parse(pointer.encode()).value("com.example.app",4,"native").equals("swipe")) throw new AssertionError("Pointer adapter round trip failed");
        reject(ConfigSpec.PROFILES,"v1\ncom.example.app|1|note|none|none|none|unknown|native");
    }
    private static void reject(String k, Object v) {
        try { ConfigSpec.validate(k, v); } catch (IllegalArgumentException expected) { return; }
        throw new AssertionError("Accepted invalid edit: " + k + "=" + v);
    }
}
