package com.oliver.erydon.migration;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.oliver.erydon.item.ErydonBlockCategories;
import com.oliver.erydon.item.ErydonItemOrdering;
import net.minecraft.item.ItemStack;
import net.minecraft.text.OrderedText;
import net.minecraft.text.StringVisitable;
import net.minecraft.text.Text;
import net.minecraft.util.Language;

import java.io.InputStreamReader;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

/** Real optional-viewer plugin registration, alias storage and EMI alias queries, without a GUI. */
public final class ViewerSearchLaunchChecks {
    private ViewerSearchLaunchChecks() { }

    public static void run() throws Exception {
        List<ItemStack> stacks = ErydonItemOrdering.orderedBlockStacks();
        require(stacks.size() == 144, "Incomplete viewer block/item fixture: " + stacks.size());
        require(stacks.stream().filter(stack -> ErydonBlockCategories.isStandardFinish(ErydonItemOrdering.path(stack))).count() == 36,
                "Viewer fixture must include 27 standard copings and 9 plain shapes");
        Language original = Language.getInstance();
        try {
            for (String locale : List.of("en_us", "de_de", "es_es")) {
                JsonObject language = resource("/assets/erydon/lang/" + locale + ".json");
                Language.setInstance(language(language));
                List<String> terms = List.of("polished", "honed", "mirror");
                List<String> localized = List.of(language.get("search.erydon.standard_finish").getAsString().split(" "));
                checkJei(stacks, terms, localized);
                checkEmi(stacks, terms, localized);
            }
        } finally {
            Language.setInstance(original);
        }
        System.out.println("ERYDON_VIEWER_ALIASES_OK: JEI alias manager and EMI alias queries; 144 placed-item fixtures, 36 standard, 3 languages");
    }

    private static void checkJei(List<ItemStack> stacks, List<String> terms, List<String> localized) throws Exception {
        Class<?> type = Class.forName("mezz.jei.api.ingredients.IIngredientType");
        Class<?> helperType = Class.forName("mezz.jei.api.ingredients.IIngredientHelper");
        Class<?> rendererType = Class.forName("mezz.jei.api.ingredients.IIngredientRenderer");
        Class<?> typedType = Class.forName("mezz.jei.api.ingredients.ITypedIngredient");
        Class<?> registrationType = Class.forName("mezz.jei.api.registration.IIngredientAliasRegistration");
        Object itemType = Class.forName("mezz.jei.api.constants.VanillaTypes").getField("ITEM_STACK").get(null);
        Object helper = proxy(helperType, (self, method, args) -> switch (method.getName()) {
            case "getIngredientType" -> itemType;
            case "getUniqueId", "getErrorInfo" -> "erydon:" + ErydonItemOrdering.path((ItemStack) args[0]);
            case "isValidIngredient" -> !((ItemStack) args[0]).isEmpty();
            case "getDisplayName" -> ((ItemStack) args[0]).getName().getString();
            default -> method.isDefault() ? InvocationHandler.invokeDefault(self, method, args) : null;
        });
        Object renderer = proxy(rendererType, (self, method, args) -> switch (method.getName()) {
            case "getWidth", "getHeight" -> 16;
            default -> method.isDefault() ? InvocationHandler.invokeDefault(self, method, args) : null;
        });
        Class<?> builderType = Class.forName("mezz.jei.library.load.registration.IngredientManagerBuilder");
        Object builder = builderType.getConstructor(
                Class.forName("mezz.jei.api.ingredients.subtypes.ISubtypeManager"),
                Class.forName("mezz.jei.api.helpers.IColorHelper")).newInstance(null, null);
        builderType.getMethod("register", type, Collection.class, helperType, rendererType)
                .invoke(builder, itemType, stacks, helper, renderer);
        Class<?> pluginType = Class.forName("com.oliver.erydon.compat.jei.ErydonJeiPlugin");
        // JEI discovers its CLASS-retained annotation from bytecode, not reflection.
        boolean[] annotated = {false};
        try (var bytecode = pluginType.getResourceAsStream("ErydonJeiPlugin.class")) {
            require(bytecode != null, "Missing JEI plugin bytecode");
            new org.objectweb.asm.ClassReader(bytecode).accept(new org.objectweb.asm.ClassVisitor(org.objectweb.asm.Opcodes.ASM9) {
                @Override public org.objectweb.asm.AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
                    if (descriptor.equals("Lmezz/jei/api/JeiPlugin;")) annotated[0] = true;
                    return null;
                }
            }, org.objectweb.asm.ClassReader.SKIP_CODE | org.objectweb.asm.ClassReader.SKIP_DEBUG | org.objectweb.asm.ClassReader.SKIP_FRAMES);
        }
        require(annotated[0], "JEI plugin discovery annotation is missing");
        pluginType.getMethod("registerIngredientAliases", registrationType).invoke(pluginType.getConstructor().newInstance(), builder);
        Object manager = builderType.getMethod("build").invoke(builder);
        Class<?> managerType = Class.forName("mezz.jei.api.runtime.IIngredientManager");
        for (ItemStack stack : stacks) {
            Object typed = ((java.util.Optional<?>) managerType.getMethod("createTypedIngredient", type, Object.class)
                    .invoke(manager, itemType, stack)).orElseThrow();
            @SuppressWarnings("unchecked")
            Collection<String> aliases = (Collection<String>) managerType.getMethod("getIngredientAliases", typedType).invoke(manager, typed);
            checkAliases("JEI", stack, aliases, terms, localized);
        }
    }

    private static void checkEmi(List<ItemStack> stacks, List<String> terms, List<String> localized) throws Exception {
        Class<?> registryType = Class.forName("dev.emi.emi.api.EmiRegistry");
        Class<?> stackType = Class.forName("dev.emi.emi.api.stack.EmiStack");
        Class<?> stackListType = Class.forName("dev.emi.emi.registry.EmiStackList");
        @SuppressWarnings("unchecked")
        List<Object> registryAliases = (List<Object>) stackListType.getField("registryAliases").get(null);
        @SuppressWarnings("unchecked")
        List<Object> sidebar = (List<Object>) stackListType.getField("stacks").get(null);
        List<Object> previousAliases = List.copyOf(registryAliases);
        registryAliases.clear();
        stackListType.getField("stacks").set(null, List.of()); // Avoid GUI-dependent tooltip rendering.
        try {
            Object registry = Class.forName("dev.emi.emi.registry.EmiRegistryImpl").getConstructor().newInstance();
            JsonObject entrypoints = erydonMetadata().getAsJsonObject("entrypoints");
            String pluginName = entrypoints.getAsJsonArray("emi").get(0).getAsString();
            Class<?> pluginType = Class.forName(pluginName);
            pluginType.getMethod("register", registryType).invoke(pluginType.getConstructor().newInstance(), registry);
            Class.forName("dev.emi.emi.search.EmiSearch").getMethod("bake").invoke(null);
            Class<?> queryType = Class.forName("dev.emi.emi.search.AliasQuery");
            Map<String, Object> queries = java.util.stream.Stream.concat(terms.stream(), localized.stream()).distinct()
                    .collect(Collectors.toMap(term -> term, term -> {
                        try { return queryType.getConstructor(String.class).newInstance(term); }
                        catch (ReflectiveOperationException failure) { throw new IllegalStateException(failure); }
                    }));
            for (ItemStack stack : stacks) {
                boolean standard = ErydonBlockCategories.isStandardFinish(ErydonItemOrdering.path(stack));
                Object emiStack = stackType.getMethod("of", ItemStack.class).invoke(null, stack);
                for (var query : queries.entrySet()) {
                    boolean matches = (boolean) queryType.getMethod("matches", stackType).invoke(query.getValue(), emiStack);
                    require(matches == standard, "Wrong EMI alias query: " + ErydonItemOrdering.path(stack) + " / " + query.getKey());
                }
            }
        } finally {
            registryAliases.clear(); registryAliases.addAll(previousAliases);
            stackListType.getField("stacks").set(null, sidebar);
        }
    }

    private static void checkAliases(String viewer, ItemStack stack, Collection<String> aliases,
                                     List<String> terms, List<String> localized) {
        String path = ErydonItemOrdering.path(stack);
        boolean standard = ErydonBlockCategories.isStandardFinish(path);
        for (String term : terms) require(aliases.contains(term) == standard, "Wrong " + viewer + " alias: " + path + " / " + term);
        String indexed = String.join(" ", aliases).toLowerCase(Locale.ROOT);
        for (String term : localized) require(indexed.contains(term) == standard, "Wrong localized " + viewer + " alias: " + path + " / " + term);
        require(aliases.containsAll(ErydonBlockCategories.searchTerms(path)), viewer + " lost existing search vocabulary: " + path);
    }

    private static Object proxy(Class<?> type, InvocationHandler handler) {
        return Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler);
    }

    private static Language language(JsonObject entries) {
        return new Language() {
            @Override public String get(String key, String fallback) { return entries.has(key) ? entries.get(key).getAsString() : fallback; }
            @Override public boolean hasTranslation(String key) { return entries.has(key); }
            @Override public boolean isRightToLeft() { return false; }
            @Override public OrderedText reorder(StringVisitable text) { return OrderedText.EMPTY; }
        };
    }

    private static JsonObject resource(String path) throws Exception {
        try (var stream = ViewerSearchLaunchChecks.class.getResourceAsStream(path)) {
            require(stream != null, "Missing viewer-test resource: " + path);
            return JsonParser.parseReader(new InputStreamReader(stream, StandardCharsets.UTF_8)).getAsJsonObject();
        }
    }

    private static JsonObject erydonMetadata() throws Exception {
        var metadata = ViewerSearchLaunchChecks.class.getClassLoader().getResources("fabric.mod.json");
        while (metadata.hasMoreElements()) {
            try (var stream = metadata.nextElement().openStream()) {
                JsonObject candidate = JsonParser.parseReader(new InputStreamReader(stream, StandardCharsets.UTF_8)).getAsJsonObject();
                if (candidate.has("id") && candidate.get("id").getAsString().equals("erydon")) return candidate;
            }
        }
        throw new AssertionError("Missing ERYDON metadata for EMI entrypoint discovery");
    }

    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
