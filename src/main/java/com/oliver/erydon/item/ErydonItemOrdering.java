package com.oliver.erydon.item;

import com.oliver.erydon.Erydon;
import com.oliver.erydon.util.ErydonIdNaming;
import com.oliver.erydon.migration.ErydonIdMigration;
import net.minecraft.block.Block;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import java.util.stream.StreamSupport;

public final class ErydonItemOrdering {
    private static final List<String> MATERIAL_PREFIXES = ErydonMaterialSources.materialPrefixes();
    private static final Map<String, Integer> MATERIAL_ORDER = index(MATERIAL_PREFIXES);

    private static final Map<String, Integer> SHAPE_ORDER = Map.ofEntries(
            Map.entry("block", 0),
            Map.entry("pane", 1),
            Map.entry("slab", 10),
            Map.entry("stairs", 20),
            Map.entry("stairs_shallow_bottom", 21),
            Map.entry("stairs_shallow_top", 22),
            Map.entry("stairs_spiral_large", 23),
            Map.entry("wall", 30),
            Map.entry("wall_georgian", 31),
            Map.entry("layer", 40),
            Map.entry("layer_multiface", 41),
            Map.entry("layer_vertical", 42),
            Map.entry("vertical_slice", 50),
            Map.entry("horizontal_slice", 51),
            Map.entry("slice_vertical", 50),
            Map.entry("slice_horizontal", 51),
            Map.entry("post", 52),
            Map.entry("slope", 60),
            Map.entry("slope_shallow_lower", 61),
            Map.entry("slope_shallow_upper", 62),
            Map.entry("slope_steep_lower", 63),
            Map.entry("slope_steep_upper", 64),
            Map.entry("slope_vertical", 65),
            Map.entry("slope_vertical_shallow_broad", 66),
            Map.entry("slope_vertical_shallow_narrow", 67),
            Map.entry("shallow_slope_lower", 68),
            Map.entry("shallow_slope_upper", 69),
            Map.entry("arch_romanesque", 80),
            Map.entry("arch_modern", 81),
            Map.entry("arch_gothic", 82),
            Map.entry("alcove_georgian", 83),
            Map.entry("alcove_gothic", 84),
            Map.entry("column_square", 90),
            Map.entry("column_circular", 91),
            Map.entry("column_gothic", 92),
            Map.entry("chimney_circular", 100),
            Map.entry("cornice_modern", 110),
            Map.entry("cornice_georgian", 111),
            Map.entry("cornice_guilloche", 112),
            Map.entry("cornice_gothic", 113),
            Map.entry("surround_modern", 120),
            Map.entry("surround_georgian", 121),
            Map.entry("surround_guilloche", 122),
            Map.entry("surround_gothic_ornate", 123),
            Map.entry("window_arch", 130),
            Map.entry("window_french_georgian", 131),
            Map.entry("light_modern", 140),
            Map.entry("light_wall", 141),
            Map.entry("light_pendant", 142),
            Map.entry("ceiling_coffered_georgian_white_small", 160),
            Map.entry("ceiling_coffered_georgian_black_small", 161),
            Map.entry("ceiling_coffered_modern_white_small", 162),
            Map.entry("ceiling_coffered_modern_black_small", 163),
            Map.entry("cover", 170),
            Map.entry("framed", 180),
            Map.entry("framed_slope", 181),
            Map.entry("framed_slope_vertical", 182),
            Map.entry("framed_shallow_slope_lower", 183),
            Map.entry("framed_shallow_slope_upper", 184)
    );

    private static final Map<String, Integer> GLAZING_ORDER = Map.of(
            "tinted", 0,
            "crystal", 1,
            "silver", 2,
            "bronze", 3
    );

    private static final Map<String, Integer> DECORATION_ORDER = Map.of(
            "bronzetrim", 0,
            "silvertrim", 1,
            "bronzeguilloche", 2,
            "silverguilloche", 3,
            "bronzequatrefoil", 4,
            "silverquatrefoil", 5,
            "bronzerose", 6,
            "silverrose", 7
    );

    private static final Map<String, String> CANONICAL_DECORATION_PREFIXES = Map.ofEntries(
            Map.entry("trim_bronze", "bronzetrim"),
            Map.entry("trim_silver", "silvertrim"),
            Map.entry("guilloche_bronze", "bronzeguilloche"),
            Map.entry("guilloche_silver", "silverguilloche"),
            Map.entry("quatrefoil_bronze", "bronzequatrefoil"),
            Map.entry("quatrefoil_silver", "silverquatrefoil"),
            Map.entry("rosette_bronze", "bronzerose"),
            Map.entry("rosette_silver", "silverrose")
    );

    private ErydonItemOrdering() {
    }

    public static List<Item> orderedBlockItems() {
        return StreamSupport.stream(Registries.BLOCK.spliterator(), false)
                .filter(ErydonItemOrdering::isErydonBlock)
                .map(Block::asItem)
                .filter(item -> item != Items.AIR)
                .sorted((left, right) -> sortKey(left).compareTo(sortKey(right)))
                .toList();
    }

    public static List<ItemStack> orderedBlockStacks() {
        return orderedBlockItems().stream()
                .map(Item::getDefaultStack)
                .toList();
    }

    public static List<ItemStack> orderedBlockStacksMatching(Predicate<String> pathPredicate) {
        return orderedBlockItems().stream()
                .map(Item::getDefaultStack)
                .filter(stack -> pathPredicate.test(path(stack)))
                .toList();
    }

    public static boolean isErydonBlockItem(ItemStack stack) {
        return stack.getItem() instanceof BlockItem && isErydonItem(stack.getItem());
    }

    public static String path(ItemStack stack) {
        return path(stack.getItem());
    }

    public static String path(Item item) {
        Identifier id = Registries.ITEM.getId(item);
        return id == null ? "" : id.getPath();
    }

    public static boolean isErydonItem(Item item) {
        Identifier id = Registries.ITEM.getId(item);
        return id != null && Erydon.MOD_ID.equals(id.getNamespace());
    }

    private static boolean isErydonBlock(Block block) {
        Identifier id = Registries.BLOCK.getId(block);
        return id != null && Erydon.MOD_ID.equals(id.getNamespace());
    }

    private static SortKey sortKey(Item item) {
        Identifier id = Registries.ITEM.getId(item);
        if (id == null) {
            return SortKey.fallback("unknown", Integer.MAX_VALUE);
        }

        String path = ErydonIdMigration.legacyResourcePath(id.getPath());
        Family family = findFamily(path);
        if (family != null) {
            return materialSortKey(path, family);
        }
        if (path.startsWith("glazing_")) {
            return glazingSortKey(path);
        }
        if (path.startsWith("cover_")) {
            return new SortKey(2, 0, "", 0, "cover", colorOrder(path), 0,
                    shapeOrder("cover"), "cover", rawItemId(item), path);
        }
        if (path.startsWith("ceiling_coffered_")) {
            return new SortKey(2, 0, "", 10, "ceiling", 0, 0,
                    shapeOrder(path), path, rawItemId(item), path);
        }
        if (path.equals("brazier")) {
            return new SortKey(3, 0, "", 0, "special", 0, 0,
                    0, path, rawItemId(item), path);
        }
        return SortKey.fallback(path, rawItemId(item));
    }

    private static SortKey materialSortKey(String path, Family family) {
        TextureShape textureShape = classifyTextureShape(family.remainder());
        int materialOrder = MATERIAL_ORDER.getOrDefault(family.material(), Integer.MAX_VALUE);
        return new SortKey(0, materialOrder, family.material(),
                textureShape.textureOrder(), textureShape.textureName(), textureShape.textureVariantOrder(),
                textureShape.ageOrder(), shapeOrder(textureShape.shapeName()), textureShape.shapeName(),
                rawPathOrder(path), path);
    }

    private static SortKey glazingSortKey(String path) {
        String rest = path.substring("glazing_".length());
        boolean framed = rest.startsWith("framed_");
        if (framed) {
            rest = rest.substring("framed_".length());
        }

        String variant = firstPart(rest);
        String shape = rest.equals(variant) ? "" : rest.substring(variant.length() + 1);
        if (shape.isEmpty()) {
            shape = framed ? "framed" : "pane";
        } else if (framed) {
            shape = "framed_" + shape;
        }

        return new SortKey(1, 0, "", GLAZING_ORDER.getOrDefault(variant, Integer.MAX_VALUE), variant,
                framed ? 1 : 0, 0, shapeOrder(shape), shape, rawPathOrder(path), path);
    }

    private static TextureShape classifyTextureShape(String remainder) {
        if (remainder.contains("_weave_")) {
            return weaveTextureShape(remainder);
        }

        boolean aged = ErydonIdNaming.isAged(remainder);
        String base = ErydonIdNaming.withoutAged(remainder);
        int ageOrder = aged ? 1 : 0;

        if (base.startsWith("ceiling_coffered_")) {
            return new TextureShape(80, "ceiling", 0, ageOrder, base);
        }

        if (base.startsWith("block_")) {
            String decoration = base.substring("block_".length());
            Integer decorationOrder = DECORATION_ORDER.get(decoration);
            if (decorationOrder != null) {
                return new TextureShape(45, "decorated", decorationOrder, ageOrder, "block");
            }
        }

        TextureShape knownTexture = knownTextureShape(base, ageOrder);
        if (knownTexture != null) {
            return knownTexture;
        }

        return new TextureShape(0, "base", 0, ageOrder, base);
    }

    private static TextureShape weaveTextureShape(String remainder) {
        int weaveIndex = remainder.indexOf("_weave_");
        String otherTexture = remainder.substring(0, weaveIndex);
        String afterWeave = remainder.substring(weaveIndex + "_weave_".length());
        String variant = firstPart(afterWeave);
        String shape = afterWeave.equals(variant) ? "block" : afterWeave.substring(variant.length() + 1);
        int variantOrder = switch (variant) {
            case "bronze" -> 0;
            case "grout" -> 1;
            default -> Integer.MAX_VALUE;
        };
        return new TextureShape(60, "weave_" + otherTexture, variantOrder, 0, shape);
    }

    private static TextureShape knownTextureShape(String base, int ageOrder) {
        if (base.equals("rock") || base.startsWith("rock_")) {
            return new TextureShape(5, "rock", 0, ageOrder, afterPrefix(base, "rock", "block"));
        }
        if (base.equals("ashlar") || base.startsWith("ashlar_")) {
            return new TextureShape(10, "ashlar", 0, ageOrder, afterPrefix(base, "ashlar", "block"));
        }
        if (base.equals("rusticated") || base.startsWith("rusticated_")) {
            return new TextureShape(20, "rusticated", 0, ageOrder, afterPrefix(base, "rusticated", "block"));
        }
        if (base.equals("hewn") || base.startsWith("hewn_")) {
            return new TextureShape(30, "hewn", 0, ageOrder, afterPrefix(base, "hewn", "block"));
        }
        if (base.equals("herringbone_grout") || base.startsWith("herringbone_grout_")) {
            return new TextureShape(40, "herringbone_grout", 0, ageOrder,
                    afterPrefix(base, "herringbone_grout", "block"));
        }
        if (base.equals("herringbone_bronze") || base.startsWith("herringbone_bronze_")) {
            return new TextureShape(41, "herringbone_bronze", 0, ageOrder,
                    afterPrefix(base, "herringbone_bronze", "block"));
        }
        for (Map.Entry<String, String> entry : CANONICAL_DECORATION_PREFIXES.entrySet()) {
            String prefix = entry.getKey();
            if (base.equals(prefix) || base.startsWith(prefix + "_")) {
                return new TextureShape(45, "decorated", DECORATION_ORDER.get(entry.getValue()), ageOrder,
                        afterPrefix(base, prefix, "block"));
            }
        }
        if (base.equals("diaphanes") || base.startsWith("diaphanes_")) {
            return new TextureShape(70, "diaphanes", 0, ageOrder, afterPrefix(base, "diaphanes", "pane"));
        }
        return null;
    }

    private static String afterPrefix(String value, String prefix, String fallbackShape) {
        if (value.equals(prefix)) {
            return fallbackShape;
        }
        return value.substring(prefix.length() + 1);
    }

    private static Family findFamily(String path) {
        if (path.contains("_weave_")) {
            String firstTexture = firstPart(path);
            if (MATERIAL_ORDER.containsKey(firstTexture)) {
                return new Family(firstTexture, path.substring(firstTexture.length() + 1));
            }
        }

        String material = findMaterialPrefix(path);
        if (material == null) {
            return null;
        }
        if (path.equals(material)) {
            return new Family(material, "block");
        }
        return new Family(material, path.substring(material.length() + 1));
    }

    private static String findMaterialPrefix(String path) {
        String best = null;
        for (String prefix : MATERIAL_PREFIXES) {
            if ((path.equals(prefix) || path.startsWith(prefix + "_"))
                    && (best == null || prefix.length() > best.length())) {
                best = prefix;
            }
        }
        return best;
    }

    private static int shapeOrder(String shape) {
        Integer order = SHAPE_ORDER.get(shape);
        if (order != null) {
            return order;
        }
        if (shape.startsWith("framed_shallow_slope_")) {
            return shapeOrder(shape.substring("framed_".length()));
        }
        return 1000;
    }

    private static int colorOrder(String path) {
        if (path.endsWith("_white")) {
            return 0;
        }
        if (path.endsWith("_black")) {
            return 1;
        }
        return 1000;
    }

    private static String firstPart(String value) {
        int index = value.indexOf('_');
        return index < 0 ? value : value.substring(0, index);
    }

    private static int rawItemId(Item item) {
        return Registries.ITEM.getRawId(item);
    }

    private static int rawPathOrder(String path) {
        Item item = Registries.ITEM.get(new Identifier(Erydon.MOD_ID, path));
        return item == Items.AIR ? Integer.MAX_VALUE : rawItemId(item);
    }

    private static Map<String, Integer> index(List<String> values) {
        Map<String, Integer> result = new HashMap<>();
        for (int i = 0; i < values.size(); i++) {
            result.put(values.get(i), i);
        }
        return Map.copyOf(result);
    }

    private record Family(String material, String remainder) {
    }

    private record TextureShape(
            int textureOrder,
            String textureName,
            int textureVariantOrder,
            int ageOrder,
            String shapeName
    ) {
    }

    private record SortKey(
            int section,
            int materialOrder,
            String materialName,
            int textureOrder,
            String textureName,
            int textureVariantOrder,
            int ageOrder,
            int shapeOrder,
            String shapeName,
            int rawOrder,
            String path
    ) implements Comparable<SortKey> {
        private static SortKey fallback(String path, int rawOrder) {
            return new SortKey(4, Integer.MAX_VALUE, "", Integer.MAX_VALUE, "", Integer.MAX_VALUE,
                    Integer.MAX_VALUE, Integer.MAX_VALUE, "", rawOrder, path);
        }

        @Override
        public int compareTo(SortKey other) {
            int result = Integer.compare(section, other.section);
            if (result != 0) return result;
            result = Integer.compare(materialOrder, other.materialOrder);
            if (result != 0) return result;
            result = materialName.compareTo(other.materialName);
            if (result != 0) return result;
            result = Integer.compare(textureOrder, other.textureOrder);
            if (result != 0) return result;
            result = textureName.compareTo(other.textureName);
            if (result != 0) return result;
            result = Integer.compare(textureVariantOrder, other.textureVariantOrder);
            if (result != 0) return result;
            result = Integer.compare(ageOrder, other.ageOrder);
            if (result != 0) return result;
            result = Integer.compare(shapeOrder, other.shapeOrder);
            if (result != 0) return result;
            result = shapeName.compareTo(other.shapeName);
            if (result != 0) return result;
            result = Integer.compare(rawOrder, other.rawOrder);
            if (result != 0) return result;
            return path.compareTo(other.path);
        }
    }
}
