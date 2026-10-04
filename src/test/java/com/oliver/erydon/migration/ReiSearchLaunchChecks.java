package com.oliver.erydon.migration;

import com.google.gson.JsonParser;
import com.oliver.erydon.item.ErydonBlockCategories;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

import java.lang.reflect.Proxy;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/** Exercises REI's actual mixed-in search cache without its GUI or renderer. */
public final class ReiSearchLaunchChecks {
    private ReiSearchLaunchChecks() { }
    public static void run() throws Exception {
        // REI's search class creates a tooltip context before calling cacheData.
        // Supply that GUI-only dependency without starting a graphics client.
        Class<?> contextType=Class.forName("me.shedaniel.rei.api.client.gui.widgets.TooltipContext");
        Object context=Proxy.newProxyInstance(contextType.getClassLoader(),new Class[]{contextType},
                (proxy,method,args) -> method.getName().equals("isSearch") ? true : null);
        Class<?> functionType=Class.forName("org.apache.commons.lang3.function.TriFunction");
        Object provider=Proxy.newProxyInstance(functionType.getClassLoader(),new Class[]{functionType},
                (proxy,method,args) -> context);
        Class.forName("me.shedaniel.rei.impl.ClientInternals").getMethod("attachInstance",Object.class,String.class)
                .invoke(null,provider,"tooltipContextProvider");
        Class<?> entryType=Class.forName("me.shedaniel.rei.api.common.entry.EntryStack");
        Class<?> textType=Class.forName("me.shedaniel.rei.impl.client.search.argument.type.TextArgumentType");
        Object search=textType.getConstructor().newInstance();
        var cache=textType.getMethod("cacheData",entryType);
        var language=JsonParser.parseReader(new InputStreamReader(
                ReiSearchLaunchChecks.class.getResourceAsStream("/assets/erydon/lang/en_us.json"),
                StandardCharsets.UTF_8)).getAsJsonObject();
        int full=0,copings=0,standard=0,checked=0;
        for(var name:language.entrySet()) {
            if(!name.getKey().startsWith("block.erydon.")) continue;
            String path=name.getKey().substring("block.erydon.".length());
            Identifier id=new Identifier("erydon",path);
            if(!Registries.ITEM.containsId(id)) Registry.register(Registries.ITEM,id,new Item(new Item.Settings()));
            ItemStack stack=Registries.ITEM.get(id).getDefaultStack();
            Object entry=Proxy.newProxyInstance(entryType.getClassLoader(),new Class[]{entryType},(proxy,method,args) -> switch(method.getName()) {
                case "getValue" -> stack;
                case "asFormattedText" -> Text.literal(name.getValue().getAsString());
                case "getIdentifier" -> id;
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> proxy==args[0];
                case "toString" -> id.toString();
                default -> null;
            });
            String indexed=(String)cache.invoke(search,entry);
            if(!indexed.contains("erydon")) throw new AssertionError("REI misses ERYDON keyword: "+path);
            if(path.endsWith("_block")) {
                if(!indexed.contains("full block")) throw new AssertionError("REI misses full-block aliases: "+path);
                full++;
            } else if(path.endsWith("_coping_georgian")) {
                if(!indexed.contains("coping") || indexed.contains("full block")) throw new AssertionError("Wrong coping search classification: "+path);
                copings++;
            }
            boolean standardFinish=ErydonBlockCategories.isStandardFinish(path);
            for(String term:java.util.List.of("polished","honed","mirror")) {
                if(indexed.contains(term)!=standardFinish) throw new AssertionError("Wrong REI standard-finish search: "+path+" / "+term);
            }
            if(standardFinish) standard++;
            checked++;
        }
        if(full!=440 || copings!=135 || standard!=1413 || checked!=9536) throw new AssertionError("Incomplete REI catalogue check");
        System.out.println("ERYDON_REI_SEARCH_OK: "+checked+" catalogue items, "+full+" full blocks, "+copings
                +" copings, "+standard+" standard-finish items through actual REI cacheData");
    }
}
