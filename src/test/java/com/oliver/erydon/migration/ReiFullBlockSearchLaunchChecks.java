package com.oliver.erydon.migration;

import com.google.gson.JsonParser;
import com.oliver.erydon.item.ErydonBlockCategories;
import me.shedaniel.rei.api.client.ClientHelper;
import me.shedaniel.rei.api.client.config.ConfigManager;
import me.shedaniel.rei.api.client.config.ConfigObject;
import me.shedaniel.rei.api.client.gui.config.SearchMode;
import me.shedaniel.rei.api.client.gui.widgets.Tooltip;
import me.shedaniel.rei.api.common.entry.EntryStack;
import me.shedaniel.rei.api.common.plugins.PluginManager;
import me.shedaniel.rei.impl.ClientInternals;
import me.shedaniel.rei.impl.client.search.argument.Argument;
import me.shedaniel.rei.impl.client.search.argument.ArgumentCache;
import me.shedaniel.rei.impl.client.search.argument.CompoundArgument;
import me.shedaniel.rei.impl.client.search.argument.type.TextArgumentType;
import me.shedaniel.rei.impl.client.search.argument.type.TooltipArgumentType;
import me.shedaniel.rei.impl.client.search.method.DefaultInputMethod;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.GameOptions;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.tag.TagKey;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

import java.io.InputStreamReader;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

/** Real REI parser, search modes, caches and mixed-in match entry point; no GUI or config files. */
public final class ReiFullBlockSearchLaunchChecks {
    private ReiFullBlockSearchLaunchChecks() { }

    public static void run() throws Exception {
        Field singleton = field(MinecraftClient.class, "instance");
        Field plugins = field(ClientInternals.class, "clientPluginManager");
        Field helpers = field(ClientInternals.class, "clientHelper");
        Object previousClient = singleton.get(null), previousPlugins = plugins.get(null), previousHelper = helpers.get(null);
        ArgumentCache previousCache = Argument.cache;
        @SuppressWarnings("unchecked")
        AtomicReference<String> languageCache = (AtomicReference<String>) field(Argument.class, "LAST_LANGUAGE").get(null);
        String previousLanguage = languageCache.get();
        try {
            ConfigObject config = proxy(ConfigObject.class, (method, args) -> switch (method) {
                case "getTooltipSearchMode", "getIdentifierSearchMode" -> SearchMode.ALWAYS;
                case "getTagSearchMode", "getModSearchMode" -> SearchMode.PREFIX;
                default -> throw new AssertionError("Unexpected config access: " + method);
            });
            ConfigManager manager = proxy(ConfigManager.class, (method, args) -> {
                require(method.equals("getConfig"), "Unexpected config manager operation " + method);
                return config;
            });
            PluginManager<?> plugin = proxy(PluginManager.class, (method, args) -> {
                require(method.equals("get") && args[0] == ConfigManager.class, "Unexpected REI plugin operation " + method);
                return manager;
            });
            ClientInternals.attachInstanceSupplier(plugin, "clientPluginManager");
            ClientInternals.attachInstanceSupplier(proxy(ClientHelper.class, (method, args) -> {
                require(method.equals("getModFromModId"), "Unexpected client helper operation " + method);
                return args[0];
            }), "clientHelper");
            MinecraftClient client = allocate(MinecraftClient.class);
            GameOptions options = allocate(GameOptions.class);
            options.language = "en_us";
            field(MinecraftClient.class, "options").set(client, options);
            singleton.set(null, client);
            languageCache.set("en_us");
            Argument.cache = new ArgumentCache();

            Map<String, List<CompoundArgument>> queries = new HashMap<>();
            for (String query : List.of("block", "blocks", "BLOCK", "\"full block\"", "\"full blocks\"",
                    "glacium", "glacium block", "block | stairs", "-block", "glacium -block", "#block", "*block", "$block", "@erydon",
                    "r/.*Slab.*/", "blockade", "slab", "georgian", "polished")) {
                queries.put(query, Argument.bakeArguments(query));
            }
            var plain = queries.get("block").get(0).get(0);
            require(plain.stream().anyMatch(argument -> argument.getArgument() instanceof TextArgumentType)
                            && plain.stream().anyMatch(argument -> argument.getArgument() instanceof TooltipArgumentType),
                    "Probe did not exercise the live ALWAYS tooltip/text search combination");
            var names = JsonParser.parseReader(new InputStreamReader(
                    ReiFullBlockSearchLaunchChecks.class.getResourceAsStream("/assets/erydon/lang/en_us.json"),
                    StandardCharsets.UTF_8)).getAsJsonObject();
            int checked = 0, full = 0, inlays = 0, nativeFalsePositives = 0;
            long hash = 1;
            for (var name : names.entrySet()) {
                if (!name.getKey().startsWith("block.erydon.")) continue;
                String path = name.getKey().substring("block.erydon.".length());
                Identifier id = new Identifier("erydon", path);
                if (!Registries.ITEM.containsId(id)) Registry.register(Registries.ITEM, id, new Item(new Item.Settings()));
                // A representative third-party tooltip contains an incidental shape word.
                // Its native REI branch must still match, while a plain block query stays strict.
                EntryStack<?> entry = entry(id, name.getValue().getAsString(), "Connects beside a full block; supports full blocks.");
                boolean expected = ErydonBlockCategories.isFullBlock(path);
                for (String query : List.of("block", "blocks", "BLOCK", "\"full block\"", "\"full blocks\"")) {
                    require(matches(entry, hash, queries.get(query)) == expected, "Wrong full-cube query: " + query + " / " + path);
                }
                require(matches(entry, hash, queries.get("glacium block")) == (expected && matches(entry, hash, queries.get("glacium"))),
                        "Material AND full-block query changed: " + path);
                require(matches(entry, hash, queries.get("-block")) != expected, "Negated block query changed: " + path);
                require(matches(entry, hash, queries.get("#block")), "Explicit tooltip search was filtered: " + path);
                if (!expected) nativeFalsePositives++;
                if (expected) {
                    full++;
                    if (ErydonBlockCategories.isDecoratedBlock(path)) inlays++;
                }
                checked++;
                hash++;
            }
            require(checked > 9000 && full == 440 && inlays > 0 && nativeFalsePositives == checked - full,
                    "Incomplete actual REI category catalogue coverage");
            EntryStack<?> slab = entry(new Identifier("erydon", "glacium_slab"), "Glacium Slab", "A full block supports this slab; blockade.");
            EntryStack<?> stairs = entry(new Identifier("erydon", "glacium_stairs"), "Glacium Stairs", "A full block supports these stairs; blockade.");
            long slabHash = hash++, stairsHash = hash++;
            require(!matches(slab, slabHash, queries.get("block")) && matches(slab, slabHash, queries.get("#block")),
                    "Native tooltip false-positive control did not demonstrate the repaired distinction");
            require(matches(stairs, stairsHash, queries.get("block | stairs")), "OR branch for ordinary stairs was lost");
            require(!matches(slab, slabHash, queries.get("block | stairs")), "OR branch leaked an unrelated slab");
            require(matches(slab, slabHash, queries.get("glacium -block")), "Material plus negation failed");
            require(matches(slab, slabHash, queries.get("blockade")), "An ordinary word containing block was filtered");
            require(matches(slab, slabHash, queries.get("slab")), "Native shape query was filtered");
            require(matches(slab, slabHash, queries.get("$block")), "Explicit tag query was filtered");
            require(matches(slab, slabHash, queries.get("@erydon")), "Explicit namespace query was filtered");
            require(matches(slab, slabHash, queries.get("r/.*Slab.*/")), "Explicit regex query was filtered");
            Identifier foreignId = new Identifier("rei_search_fixture", "decorative_block_stairs");
            if (!Registries.ITEM.containsId(foreignId)) Registry.register(Registries.ITEM, foreignId, new Item(new Item.Settings()));
            EntryStack<?> foreign = entry(foreignId, "Foreign Block Stairs", "Foreign full block instructions");
            require(matches(foreign, hash++, queries.get("block")), "Another namespace's native block search changed");
            require(!matches(foreign, hash++, queries.get("slab")), "Another namespace's negative native result changed");
            require(!matches(slab, slabHash, queries.get("*block")), "Explicit identifier search acquired category semantics");
            System.out.println("ERYDON_REI_FULL_BLOCK_QUERY_OK catalogue=" + checked + " fullCubes=" + full
                    + " fullCubeInlays=" + inlays + " nativeTooltipFalsePositives=" + nativeFalsePositives
                    + " parser=actual matcher=actual quotesPluralNegationAndOrPrefixesForeign=preserved");
        } finally {
            Argument.cache = previousCache;
            languageCache.set(previousLanguage);
            singleton.set(null, previousClient);
            plugins.set(null, previousPlugins);
            helpers.set(null, previousHelper);
        }
    }

    private static boolean matches(EntryStack<?> entry, long hash, List<CompoundArgument> filter) {
        return Argument.matches(entry, hash, filter, DefaultInputMethod.INSTANCE);
    }

    private static EntryStack<?> entry(Identifier id, String name, String extraTooltip) {
        ItemStack stack = Registries.ITEM.get(id).getDefaultStack();
        Tooltip.Entry line = proxy(Tooltip.Entry.class, (method, args) -> switch (method) {
            case "isText" -> true;
            case "getAsText" -> Text.literal(name + "\n" + extraTooltip);
            default -> throw new AssertionError("Unexpected tooltip entry call " + method);
        });
        Tooltip tooltip = proxy(Tooltip.class, (method, args) -> {
            require(method.equals("entries"), "Unexpected tooltip call " + method);
            return List.of(line);
        });
        return proxy(EntryStack.class, (method, args) -> switch (method) {
            case "getValue" -> stack;
            case "asFormattedText" -> Text.literal(name);
            case "getIdentifier" -> id;
            case "getContainingNamespace" -> id.getNamespace();
            case "getTooltip" -> tooltip;
            case "getTagsFor" -> Stream.of(TagKey.of(RegistryKeys.ITEM, new Identifier("rei_search_fixture", "block_shapes")));
            default -> throw new AssertionError("Unexpected entry call " + method);
        });
    }

    @FunctionalInterface private interface Handler { Object invoke(String method, Object[] args) throws Throwable; }
    private static <T> T proxy(Class<T> type, Handler handler) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (proxy, method, args) -> {
            if (method.getName().equals("hashCode")) return System.identityHashCode(proxy);
            if (method.getName().equals("equals")) return proxy == args[0];
            if (method.getName().equals("toString")) return type.getSimpleName() + " fixture";
            return handler.invoke(method.getName(), args);
        }));
    }
    private static Field field(Class<?> owner, String name) throws Exception {
        Field result = owner.getDeclaredField(name); result.setAccessible(true); return result;
    }
    private static <T> T allocate(Class<T> type) throws Exception {
        return type.cast(((sun.misc.Unsafe) field(sun.misc.Unsafe.class, "theUnsafe").get(null)).allocateInstance(type));
    }
    private static void require(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
