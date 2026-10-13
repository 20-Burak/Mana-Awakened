package com.manaawakened.compat;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Checks, once when an adapter starts, that the installed build of another mod has what the adapter needs (1.0.0). Every class,
 * method and field an adapter touches is listed as a {@link Symbol} by name only, grouped into {@link Feature}s, and looked up through
 * {@link MethodHandles} against the installed classes; a symbol may list several names, tried in order, for a member that was
 * renamed between builds. The handles are kept, so nothing is looked up again while playing.
 *
 * <p>A feature whose symbols are all there is on; one with a symbol missing is off, and the adapter goes on without that part. If an
 * {@link Feature#essential() essential} feature is off, the adapter cannot work at all and the mod's integration is switched off with
 * one warning ({@link Unavailable}). The survey of every Modrinth build for Minecraft 1.21.1 (DEVELOPING.md) found no member that
 * differs, so the adapters call the mods directly and every symbol has one name; a later rename only needs its new name added here.
 * No third-party type appears in this class.
 */
public final class ApiProbe {
    private ApiProbe() {
    }

    public enum Kind {
        /** A class or interface. */
        CLASS,
        /** An instance method (inherited ones included). */
        METHOD,
        STATIC_METHOD,
        /** A public instance field (inherited ones included). */
        FIELD,
        STATIC_FIELD,
        CONSTRUCTOR
    }

    /**
     * One thing an adapter uses. {@code owner} is a binary class name ({@code a.b.Outer$Inner}); {@code names} are the names to try, in
     * order; {@code type} is the return type of a method or the type of a field, and {@code params} the parameter types, all as class
     * names or primitive names ({@code float}, {@code void}...).
     */
    public record Symbol(Kind kind, String owner, List<String> names, String type, List<String> params) {
        public static Symbol type(String owner) {
            return new Symbol(Kind.CLASS, owner, List.of(), "", List.of());
        }

        public static Symbol method(String owner, String names, String returns, String... params) {
            return new Symbol(Kind.METHOD, owner, List.of(names.split("\\|")), returns, List.of(params));
        }

        public static Symbol staticMethod(String owner, String names, String returns, String... params) {
            return new Symbol(Kind.STATIC_METHOD, owner, List.of(names.split("\\|")), returns, List.of(params));
        }

        public static Symbol field(String owner, String names, String type) {
            return new Symbol(Kind.FIELD, owner, List.of(names.split("\\|")), type, List.of());
        }

        public static Symbol staticField(String owner, String names, String type) {
            return new Symbol(Kind.STATIC_FIELD, owner, List.of(names.split("\\|")), type, List.of());
        }

        public static Symbol constructor(String owner, String... params) {
            return new Symbol(Kind.CONSTRUCTOR, owner, List.of(), "void", List.of(params));
        }

        /** {@code Owner.name(params)} for logs, with the short class names. */
        public String describe() {
            String simple = owner.substring(owner.lastIndexOf('.') + 1);
            return switch (kind) {
                case CLASS -> simple;
                case CONSTRUCTOR -> simple + "(" + String.join(", ", shortNames(params)) + ")";
                case FIELD, STATIC_FIELD -> simple + "." + String.join("|", names);
                default -> simple + "." + String.join("|", names) + "(" + String.join(", ", shortNames(params)) + ")";
            };
        }

        private static List<String> shortNames(List<String> types) {
            List<String> out = new ArrayList<>();
            for (String type : types) {
                out.add(type.substring(type.lastIndexOf('.') + 1));
            }
            return out;
        }
    }

    /** A part of an integration that works only when all its symbols are there. */
    public record Feature(String id, boolean essential, List<Symbol> symbols) {
        public static Feature essential(String id, Symbol... symbols) {
            return new Feature(id, true, List.of(symbols));
        }

        public static Feature optional(String id, Symbol... symbols) {
            return new Feature(id, false, List.of(symbols));
        }
    }

    /** What a probe found: the features that are on, the symbols that are missing, and the handles of the members that resolved. */
    public static final class Result {
        private final Map<String, List<String>> missing = new LinkedHashMap<>();
        private final Map<String, Boolean> essential = new LinkedHashMap<>();
        private final Map<Symbol, MethodHandle> handles = new LinkedHashMap<>();

        public boolean isOn(String feature) {
            List<String> gone = missing.get(feature);
            return gone != null && gone.isEmpty();
        }

        /** Whether every essential feature is on, so the adapter can work. */
        public boolean usable() {
            for (Map.Entry<String, Boolean> entry : essential.entrySet()) {
                if (entry.getValue() && !isOn(entry.getKey())) {
                    return false;
                }
            }
            return true;
        }

        /** The features that are off, each with the symbols it lacks, e.g. {@code "client_sync (missing SyncManaPacket(MagicData))"}. */
        public List<String> offFeatures() {
            List<String> off = new ArrayList<>();
            for (Map.Entry<String, List<String>> entry : missing.entrySet()) {
                if (!entry.getValue().isEmpty()) {
                    off.add(entry.getKey() + " (missing " + String.join(", ", entry.getValue()) + ")");
                }
            }
            return off;
        }

        /** The handle a member symbol resolved to, or {@code null} for a class or a missing member. */
        public MethodHandle handle(Symbol symbol) {
            return handles.get(symbol);
        }
    }

    /** Looks every symbol of {@code features} up in the classes {@code loader} sees. Never throws. */
    public static Result resolve(ClassLoader loader, List<Feature> features) {
        Result result = new Result();
        for (Feature feature : features) {
            List<String> gone = new ArrayList<>();
            for (Symbol symbol : feature.symbols()) {
                if (!resolve(loader, symbol, result)) {
                    gone.add(symbol.describe());
                }
            }
            result.missing.put(feature.id(), gone);
            result.essential.put(feature.id(), feature.essential());
        }
        return result;
    }

    private static boolean resolve(ClassLoader loader, Symbol symbol, Result result) {
        try {
            Class<?> owner = Class.forName(symbol.owner(), false, loader);
            if (symbol.kind() == Kind.CLASS) {
                return true;
            }
            MethodHandles.Lookup lookup = MethodHandles.publicLookup();
            Class<?> type = type(loader, symbol.type());
            Class<?>[] params = new Class<?>[symbol.params().size()];
            for (int i = 0; i < params.length; i++) {
                params[i] = type(loader, symbol.params().get(i));
            }
            if (symbol.kind() == Kind.CONSTRUCTOR) {
                result.handles.put(symbol, lookup.findConstructor(owner, MethodType.methodType(void.class, params)));
                return true;
            }
            for (String name : symbol.names()) {
                MethodHandle handle = find(lookup, owner, symbol.kind(), name, type, params);
                if (handle != null) {
                    result.handles.put(symbol, handle);
                    return true;
                }
            }
            return false;
        } catch (ReflectiveOperationException | LinkageError | RuntimeException failure) {
            return false;
        }
    }

    private static MethodHandle find(MethodHandles.Lookup lookup, Class<?> owner, Kind kind, String name, Class<?> type, Class<?>[] params) {
        try {
            return switch (kind) {
                case METHOD -> lookup.findVirtual(owner, name, MethodType.methodType(type, params));
                case STATIC_METHOD -> lookup.findStatic(owner, name, MethodType.methodType(type, params));
                case FIELD -> lookup.findGetter(owner, name, type);
                case STATIC_FIELD -> lookup.findStaticGetter(owner, name, type);
                default -> null;
            };
        } catch (ReflectiveOperationException | LinkageError | RuntimeException failure) {
            return null;
        }
    }

    private static Class<?> type(ClassLoader loader, String name) throws ClassNotFoundException {
        return switch (name) {
            case "void" -> void.class;
            case "boolean" -> boolean.class;
            case "int" -> int.class;
            case "long" -> long.class;
            case "float" -> float.class;
            case "double" -> double.class;
            default -> Class.forName(name, false, loader);
        };
    }

    /** Thrown by an adapter that cannot work with the installed build: its essential features are off. */
    public static final class Unavailable extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public Unavailable(List<String> offFeatures) {
            super("missing " + String.join("; ", offFeatures));
        }
    }
}
