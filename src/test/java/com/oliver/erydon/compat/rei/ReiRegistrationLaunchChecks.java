package com.oliver.erydon.compat.rei;

import com.oliver.erydon.item.ErydonItemOrdering;
import me.shedaniel.rei.api.client.plugins.REIClientPlugin;
import me.shedaniel.rei.api.client.registry.entry.CollapsibleEntryRegistry;
import me.shedaniel.rei.api.client.registry.entry.EntryRegistry;
import me.shedaniel.rei.api.client.subsets.SubsetsRegistry;
import me.shedaniel.rei.api.common.entry.EntryStack;
import net.minecraft.block.Blocks;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.text.Text;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

/** Runs the actual plugin callbacks/predicates against the former per-category filtering path. */
@SuppressWarnings("removal")
public final class ReiRegistrationLaunchChecks {
    private static final String PLUGIN = "com/oliver/erydon/compat/rei/ErydonReiClientPlugin";
    private static final String CHECKS = "com/oliver/erydon/compat/rei/ReiRegistrationLaunchChecks";
    private static int orderedLists;

    private ReiRegistrationLaunchChecks() { }

    public static void run() throws Exception {
        REIClientPlugin current = instrumented(false);
        REIClientPlugin previous = instrumented(true);
        List<List<Object>> actual = capture(current);
        List<List<Object>> expected = capture(previous);
        require(actual.equals(expected), "REI entry/group/subset names, membership or order changed");
        require(actual.stream().anyMatch(event -> event.get(0).equals("group")), "No nonempty REI groups exercised");
        require(actual.stream().anyMatch(event -> event.get(0).equals("registerPathEntries")), "No REI subsets exercised");
        System.out.println("ERYDON_REI_REGISTRATION_OK events=" + actual.size()
                + " exactEntryGroupSubsetOrder=true sortsPerReload=3 freshCategoryStacks=true");
    }

    private static List<List<Object>> capture(REIClientPlugin plugin) {
        List<List<Object>> events = new ArrayList<>();
        Set<ItemStack> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        InvocationHandler registry = (proxy, method, args) -> {
            List<Object> event = new ArrayList<>();
            event.add(method.getName());
            for (Object argument : args) {
                if (argument instanceof Collection<?> entries) {
                    List<Object> values = new ArrayList<>();
                    for (Object value : entries) {
                        ItemStack stack = (ItemStack) ((EntryStack<?>) value).getValue();
                        require(seen.add(stack), "REI categories share a mutable default ItemStack");
                        values.add(List.of(stack.getItem(), stack.getCount(),
                                stack.getNbt() == null ? "" : stack.getNbt().toString()));
                    }
                    event.add(values);
                } else if (argument instanceof Predicate<?> predicate) {
                    @SuppressWarnings("unchecked") Predicate<EntryStack<?>> remove = (Predicate<EntryStack<?>>) predicate;
                    event.add(List.of(remove.test(entry(Blocks.STONE.asItem().getDefaultStack())),
                            remove.test(entry(ErydonItemOrdering.orderedBlockItems().get(0).getDefaultStack()))));
                } else {
                    event.add(argument instanceof Text text ? text.getString() : argument.toString());
                }
            }
            events.add(event);
            if (method.getReturnType() == boolean.class) return false;
            if (method.getReturnType() == int.class) return 0;
            return null;
        };
        orderedLists = 0;
        plugin.registerEntries(proxy(EntryRegistry.class, registry));
        require(orderedLists == 1, "REI entry registration did not sort exactly once");
        orderedLists = 0;
        plugin.registerCollapsibleEntries(proxy(CollapsibleEntryRegistry.class, registry));
        require(orderedLists == 1, "REI collapsible registration did not sort exactly once");
        orderedLists = 0;
        plugin.registerSubsets(proxy(SubsetsRegistry.class, registry));
        require(orderedLists == 1, "REI subset registration did not sort exactly once");
        return events;
    }

    // Only the test-loaded plugin calls these adapters. Its registration code and
    // predicates remain the compiled production methods; no global REI state is replaced.
    public static List<Item> orderedItems() {
        orderedLists++;
        return ErydonItemOrdering.orderedBlockItems();
    }

    public static List<ItemStack> orderedStacks() {
        orderedLists++;
        return ErydonItemOrdering.orderedBlockStacks();
    }

    public static List<EntryStack<?>> previousEntries(List<Item> ignored, Predicate<String> predicate) {
        return ErydonItemOrdering.orderedBlockStacksMatching(predicate).stream()
                .<EntryStack<?>>map(ReiRegistrationLaunchChecks::entry).toList();
    }

    public static EntryStack<ItemStack> entry(ItemStack stack) {
        return proxy(EntryStack.class, (proxy, method, args) -> switch (method.getName()) {
            case "getValue", "castValue" -> stack;
            case "hashCode" -> System.identityHashCode(proxy);
            case "equals" -> proxy == args[0];
            case "toString" -> stack.toString();
            default -> throw new AssertionError("Unexpected entry-stack operation " + method);
        });
    }

    private static REIClientPlugin instrumented(boolean previousFiltering) throws Exception {
        ClassWriter writer = new ClassWriter(0);
        try (var source = ReiRegistrationLaunchChecks.class.getClassLoader().getResourceAsStream(PLUGIN + ".class")) {
            require(source != null, "Compiled REI plugin missing");
            new ClassReader(source).accept(new ClassVisitor(Opcodes.ASM9, writer) {
                @Override public MethodVisitor visitMethod(int access, String name, String descriptor,
                                                           String signature, String[] exceptions) {
                    return new MethodVisitor(Opcodes.ASM9, super.visitMethod(access, name, descriptor, signature, exceptions)) {
                        @Override public void visitMethodInsn(int opcode, String owner, String name, String descriptor, boolean isInterface) {
                            if (opcode == Opcodes.INVOKESTATIC) {
                                if (owner.equals("com/oliver/erydon/item/ErydonItemOrdering")) {
                                    if (name.equals("orderedBlockItems")) { owner = CHECKS; name = "orderedItems"; }
                                    else if (name.equals("orderedBlockStacks")) { owner = CHECKS; name = "orderedStacks"; }
                                } else if (owner.equals("me/shedaniel/rei/api/common/util/EntryStacks") && name.equals("of")) {
                                    owner = CHECKS; name = "entry";
                                } else if (previousFiltering && owner.equals(PLUGIN) && name.equals("entryStacks")) {
                                    owner = CHECKS; name = "previousEntries";
                                }
                            }
                            super.visitMethodInsn(opcode, owner, name, descriptor, isInterface);
                        }

                        @Override public void visitInvokeDynamicInsn(String name, String descriptor,
                                                                     org.objectweb.asm.Handle bootstrap, Object... arguments) {
                            // EntryStacks::of is a method reference, so replace only its
                            // factory target; the surrounding production stream is unchanged.
                            for (int i = 0; i < arguments.length; i++) {
                                if (arguments[i] instanceof org.objectweb.asm.Handle handle
                                        && handle.getOwner().equals("me/shedaniel/rei/api/common/util/EntryStacks")
                                        && handle.getName().equals("of")) {
                                    arguments[i] = new org.objectweb.asm.Handle(handle.getTag(), CHECKS,
                                            "entry", handle.getDesc(), false);
                                }
                            }
                            super.visitInvokeDynamicInsn(name, descriptor, bootstrap, arguments);
                        }
                    };
                }
            }, 0);
        }
        byte[] bytes = writer.toByteArray();
        Class<?> type = new ClassLoader(ReiRegistrationLaunchChecks.class.getClassLoader()) {
            Class<?> define() { return defineClass(PLUGIN.replace('/', '.'), bytes, 0, bytes.length); }
        }.define();
        return (REIClientPlugin) type.getConstructor().newInstance();
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, InvocationHandler handler) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
