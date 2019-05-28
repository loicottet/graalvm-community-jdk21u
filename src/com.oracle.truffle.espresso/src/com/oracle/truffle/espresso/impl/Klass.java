/*
 * Copyright (c) 2018, 2018, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */

package com.oracle.truffle.espresso.impl;

import static com.oracle.truffle.espresso.classfile.Constants.REF_invokeVirtual;
import static com.oracle.truffle.espresso.runtime.MethodHandleIntrinsics.PolySigIntrinsics.InvokeBasic;
import static com.oracle.truffle.espresso.runtime.MethodHandleIntrinsics.PolySigIntrinsics.InvokeGeneric;
import static com.oracle.truffle.espresso.runtime.MethodHandleIntrinsics.PolySigIntrinsics.LinkToInterface;
import static com.oracle.truffle.espresso.runtime.MethodHandleIntrinsics.PolySigIntrinsics.LinkToSpecial;
import static com.oracle.truffle.espresso.runtime.MethodHandleIntrinsics.PolySigIntrinsics.LinkToStatic;
import static com.oracle.truffle.espresso.runtime.MethodHandleIntrinsics.PolySigIntrinsics.LinkToVirtual;
import static com.oracle.truffle.espresso.substitutions.Target_java_lang_invoke_MethodHandleNatives.toBasic;

import java.util.function.Function;
import java.util.function.IntFunction;

import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.espresso.classfile.ConstantPool;
import com.oracle.truffle.espresso.classfile.Constants;
import com.oracle.truffle.espresso.descriptors.Symbol;
import com.oracle.truffle.espresso.descriptors.Symbol.Name;
import com.oracle.truffle.espresso.descriptors.Symbol.Signature;
import com.oracle.truffle.espresso.descriptors.Symbol.Type;
import com.oracle.truffle.espresso.descriptors.Types;
import com.oracle.truffle.espresso.meta.EspressoError;
import com.oracle.truffle.espresso.meta.JavaKind;
import com.oracle.truffle.espresso.meta.ModifiersProvider;
import com.oracle.truffle.espresso.nodes.EspressoBaseNode;
import com.oracle.truffle.espresso.nodes.MHInvokeBasicNode;
import com.oracle.truffle.espresso.nodes.MHInvokeGenericNode;
import com.oracle.truffle.espresso.nodes.MHLinkToNode;
import com.oracle.truffle.espresso.runtime.EspressoContext;
import com.oracle.truffle.espresso.runtime.EspressoException;
import com.oracle.truffle.espresso.runtime.MethodHandleIntrinsics;
import com.oracle.truffle.espresso.runtime.StaticObject;
import com.oracle.truffle.espresso.substitutions.Host;
import com.oracle.truffle.espresso.vm.InterpreterToVM;
import com.oracle.truffle.object.DebugCounter;

public abstract class Klass implements ModifiersProvider, ContextAccess {

    public static final Klass[] EMPTY_ARRAY = new Klass[0];

    static final DebugCounter methodLookupCount = DebugCounter.create("methodLookupCount");
    static final DebugCounter fieldLookupCount = DebugCounter.create("fieldLookupCount");
    static final DebugCounter declaredMethodLookupCount = DebugCounter.create("declaredMethodLookupCount");
    static final DebugCounter declaredFieldLookupCount = DebugCounter.create("declaredFieldLookupCount");

    private final Symbol<Name> name;
    private final Symbol<Type> type;
    private final JavaKind kind;
    private final EspressoContext context;
    private final ObjectKlass superKlass;

    @CompilationFinal(dimensions = 1) //
    private final ObjectKlass[] superInterfaces;

    @CompilationFinal //
    private volatile ArrayKlass arrayClass;

    @CompilationFinal //
    private volatile StaticObject mirrorCache;

    private final boolean isArray;

    @CompilationFinal private int hierarchyDepth = -1;

    public final ObjectKlass[] getSuperInterfaces() {
        return superInterfaces;
    }

    public Klass(EspressoContext context, Symbol<Name> name, Symbol<Type> type, ObjectKlass superKlass, ObjectKlass[] superInterfaces) {
        this.context = context;
        this.name = name;
        this.type = type;
        this.kind = Types.getJavaKind(type);
        this.superKlass = superKlass;
        this.superInterfaces = superInterfaces;
        this.isArray = Types.isArray(type);
    }

    public abstract @Host(ClassLoader.class) StaticObject getDefiningClassLoader();

    public abstract ConstantPool getConstantPool();

    public final JavaKind getJavaKind() {
        return kind;
    }

    public final boolean isArray() {
        return isArray;
    }

    public StaticObject mirror() {
        if (mirrorCache == null) {
            mirrorCreate();
        }
        return mirrorCache;
    }

    private synchronized void mirrorCreate() {
        if (mirrorCache == null) {
            CompilerDirectives.transferToInterpreterAndInvalidate();
            this.mirrorCache = new StaticObject(getMeta().Class, this);
        }
    }

    @Override
    public final boolean equals(Object obj) {
        if (!(obj instanceof Klass)) {
            return false;
        }
        Klass that = (Klass) obj;
        return this.mirror().equals(that.mirror());
    }

    public final ArrayKlass getArrayClass() {
        ArrayKlass ak = arrayClass;
        if (ak == null) {
            CompilerDirectives.transferToInterpreterAndInvalidate();
            synchronized (this) {
                ak = arrayClass;
                if (ak == null) {
                    ak = createArrayKlass();
                    arrayClass = ak;
                }
            }
        }
        return ak;
    }

    public final ArrayKlass array() {
        return getArrayClass();
    }

    public ArrayKlass getArrayClass(int dimensions) {
        assert dimensions > 0;
        ArrayKlass array = getArrayClass();

        // Careful with of impossible void[].
        if (array == null) {
            return null;
        }

        for (int i = 1; i < dimensions; ++i) {
            array = array.getArrayClass();
        }
        return array;
    }

    protected ArrayKlass createArrayKlass() {
        return new ArrayKlass(this);
    }

    @Override
    public final int hashCode() {
        return getType().hashCode();
    }

    @Override
    public final EspressoContext getContext() {
        return context;
    }

    public final StaticObject tryInitializeAndGetStatics() {
        safeInitialize();
        return getStatics();
    }

    public abstract StaticObject getStatics();

    /**
     * Checks whether this type is an instance class.
     *
     * @return {@code true} if this type is an instance class
     */
    public abstract boolean isInstanceClass();

    /**
     * Checks whether this type is primitive.
     *
     * @return {@code true} if this type is primitive
     */
    public final boolean isPrimitive() {
        return kind.isPrimitive();
    }

    /*
     * The setting of the final bit for types is a bit confusing since arrays are marked as final.
     * This method provides a semantically equivalent test that appropriate for types.
     */
    public boolean isLeaf() {
        return getElementalType().isFinalFlagSet();
    }

    /**
     * Checks whether this type is initialized. If a type is initialized it implies that it was
     * linked and that the static initializer has run.
     *
     * @return {@code true} if this type is initialized
     */
    public abstract boolean isInitialized();

    /**
     * Initializes this type.
     */
    public abstract void initialize();

    /**
     * Determines if this type is either the same as, or is a superclass or superinterface of, the
     * type represented by the specified parameter. This method is identical to
     * {@link Class#isAssignableFrom(Class)} in terms of the value return for this type.
     * 
     * Fast check for Object types (as opposed to interface types) -> do not need to walk the entire
     * class hierarchy.
     * 
     * Interface check is still slow, though.
     */
    @TruffleBoundary
    public final boolean isAssignableFrom(Klass other) {
        if (this == other) {
            return true;
        }
        if (this.isPrimitive() || other.isPrimitive()) {
            // Reference equality is enough within the same context.
            assert this.getContext() == other.getContext();
            return this == other;
        }
        if (this.isArray() && other.isArray()) {
            return this.getComponentType().isAssignableFrom(other.getComponentType());
        }
        if (isInterface()) {
            for (Klass k : other.getTransitiveInterfacesList()) {
                if (k == this) {
                    return true;
                }
            }
            return false;
        }
        int depth = getHierarchyDepth();
        return other.getHierarchyDepth() >= depth && other.getSuperTypes()[depth] == this;
    }

    public final Klass getClosestCommonSupertype(Klass other) {
        if (isPrimitive() || other.isPrimitive()) {
            if (this == other) {
                return this;
            }
            return null;
        }
        Klass[] thisHierarchy = getSuperTypes();
        Klass[] otherHierarchy = other.getSuperTypes();
        for (int i = Math.min(getHierarchyDepth(), other.getHierarchyDepth()); i >= 0; i--) {
            if (thisHierarchy[i] == otherHierarchy[i]) {
                return thisHierarchy[i];
            }
        }
        throw EspressoError.shouldNotReachHere("Klasses should be either primitives, or have j.l.Object as common supertype.");
    }

    /**
     * Returns the {@link Klass} object representing the host class of this VM anonymous class (as
     * opposed to the unrelated concept specified by {@link Class#isAnonymousClass()}) or
     * {@code null} if this object does not represent a VM anonymous class.
     */
    public Klass getHostClass() {
        return null;
    }

    /**
     * Returns true if this type is exactly the type {@link java.lang.Object}.
     */
    public boolean isJavaLangObject() {
        // Removed assertion due to https://bugs.eclipse.org/bugs/show_bug.cgi?id=434442
        return getSuperKlass() == null && !isInterface() && getJavaKind() == JavaKind.Object;
    }

    /**
     * Gets the super class of this type. If this type represents either the {@code Object} class,
     * an interface, a primitive type, or void, then null is returned. If this object represents an
     * array class then the type object representing the {@code Object} class is returned.
     */
    public final ObjectKlass getSuperKlass() {
        return superKlass;
    }

    /**
     * Gets the interfaces implemented or extended by this type. This method is analogous to
     * {@link Class#getInterfaces()} and as such, only returns the interfaces directly implemented
     * or extended by this type.
     */
    public final ObjectKlass[] getInterfaces() {
        return superInterfaces;
    }

    public abstract Klass getElementalType();

    /**
     * Returns {@code true} if the type is a local type.
     */
    public abstract boolean isLocal();

    /**
     * Returns {@code true} if the type is a member type.
     */
    public abstract boolean isMember();

    /**
     * Returns the enclosing type of this type, if it exists, or {@code null}.
     */
    public abstract Klass getEnclosingType();

    /**
     * Returns an array reflecting all the constructors declared by this type. This method is
     * similar to {@link Class#getDeclaredConstructors()} in terms of returned constructors.
     */
    public abstract Method[] getDeclaredConstructors();

    /**
     * Returns an array reflecting all the methods declared by this type. This method is similar to
     * {@link Class#getDeclaredMethods()} in terms of returned methods.
     */
    public abstract Method[] getDeclaredMethods();

    /**
     * Returns an array reflecting all the fields declared by this type. This method is similar to
     * {@link Class#getDeclaredFields()} in terms of returned fields.
     */
    public abstract Field[] getDeclaredFields();

    /**
     * Returns the {@code <clinit>} method for this class if there is one.
     */
    public Method getClassInitializer() {
        return lookupDeclaredMethod(Name.CLINIT, Signature._void);
    }

    public final Symbol<Type> getType() {
        return type;
    }

    @Override
    public String toString() {
        return "Klass<" + getType() + ">";
    }

    // region Meta.Klass

    public void safeInitialize() {
        try {
            initialize();
        } catch (EspressoException e) {
            StaticObject cause = e.getException();
            if (getMeta().Exception.isAssignableFrom(cause.getKlass())) {
                throw getMeta().throwExWithCause(ExceptionInInitializerError.class, cause);
            } else {
                throw e;
            }
        } catch (VerifyError | ClassFormatError | IncompatibleClassChangeError | NoClassDefFoundError e) {
            ((ObjectKlass) this).setErroneous();
            throw getMeta().throwExWithMessage(e.getClass(), e.getMessage());
        }
    }

    public abstract Klass getComponentType();

    public final Klass getSupertype() {
        if (isPrimitive()) {
            return null;
        }
        if (isArray()) {
            Klass component = getComponentType();
            if (this == getMeta().Object.array() || component.isPrimitive()) {
                return getMeta().Object;
            }
            return component.getSupertype().array();
        }
        if (isInterface()) {
            return getMeta().Object;
        }
        return getSuperKlass();
    }

    public final boolean isPrimaryType() {
        assert !isPrimitive();
        if (isArray()) {
            return getElementalType().isPrimaryType();
        }
        return !isInterface();
    }

    @CompilationFinal(dimensions = 1) //
    private Klass[] supertypesWithSelfCache;

    // index 0 is Object, index hierarchyDepth is this
    @TruffleBoundary
    private Klass[] getSuperTypes() {
        Klass[] supertypes = supertypesWithSelfCache;
        if (supertypes == null) {
            CompilerDirectives.transferToInterpreterAndInvalidate();
            Klass supertype = getSupertype();
            if (supertype == null) {
                this.supertypesWithSelfCache = new Klass[]{this};
                return supertypesWithSelfCache;
            }
            Klass[] superKlassTypes = supertype.getSuperTypes();
            supertypes = new Klass[superKlassTypes.length + 1];
            int depth = getHierarchyDepth();
            assert supertypes.length == depth + 1;
            supertypes[depth] = this;
            System.arraycopy(superKlassTypes, 0, supertypes, 0, depth);
            supertypesWithSelfCache = supertypes;
        }
        return supertypes;
    }

    private int getHierarchyDepth() {
        int result = hierarchyDepth;
        if (result == -1) {
            if (getSupertype() == null) {
                // Primitives or java.lang.Object
                result = 0;
            } else {
                result = getSupertype().getHierarchyDepth() + 1;
            }
            hierarchyDepth = result;
        }
        return result;
    }

    @CompilationFinal(dimensions = 1) private Klass[] transitiveInterfaceCache;

    @TruffleBoundary
    protected final Klass[] getTransitiveInterfacesList() {
        Klass[] transitiveInterfaces = transitiveInterfaceCache;
        if (transitiveInterfaces == null) {
            CompilerDirectives.transferToInterpreterAndInvalidate();
            if (this.isArray() || this.isPrimitive()) {
                transitiveInterfaces = this.getSuperInterfaces();
            } else {
                // Use the itable construction.
                transitiveInterfaces = ((ObjectKlass) this).getiKlassTable();
            }
            transitiveInterfaceCache = transitiveInterfaces;
        }
        return transitiveInterfaces;
    }

    @TruffleBoundary
    public StaticObject allocateArray(int length) {
        return InterpreterToVM.newArray(this, length);
    }

    @TruffleBoundary
    public StaticObject allocateArray(int length, IntFunction<StaticObject> generator) {
        // TODO(peterssen): Store check is missing.
        StaticObject[] array = new StaticObject[length];
        for (int i = 0; i < array.length; ++i) {
            array[i] = generator.apply(i);
        }
        return new StaticObject(getArrayClass(), array);
    }

    // region Lookup

    public final Field lookupDeclaredField(Symbol<Name> fieldName, Symbol<Type> fieldType) {
        declaredFieldLookupCount.inc();
        // TODO(peterssen): Improve lookup performance.
        for (Field field : getDeclaredFields()) {
            if (fieldName.equals(field.getName()) && fieldType.equals(field.getType())) {
                return field;
            }
        }
        return null;
    }

    public final Field lookupField(Symbol<Name> fieldName, Symbol<Type> fieldType) {
        fieldLookupCount.inc();
        // TODO(peterssen): Improve lookup performance.
        Field field = lookupDeclaredField(fieldName, fieldType);
        if (field == null && getSuperKlass() != null) {
            return getSuperKlass().lookupField(fieldName, fieldType);
        }
        return field;
    }

    public abstract Field lookupFieldTable(int slot);

    public abstract Field lookupStaticFieldTable(int slot);

    public final Method lookupDeclaredMethod(Symbol<Name> methodName, Symbol<Signature> signature) {
        declaredMethodLookupCount.inc();
        // TODO(peterssen): Improve lookup performance.
        for (Method method : getDeclaredMethods()) {
            if (methodName.equals(method.getName()) && signature.equals(method.getRawSignature())) {
                return method;
            }
        }
        return null;
    }

    public abstract Method lookupMethod(Symbol<Name> methodName, Symbol<Signature> signature);

    public abstract Method vtableLookup(int vtableIndex);

    public Method lookupPolysigMethod(Symbol<Name> methodName, Symbol<Signature> signature) {
        if (methodName == Name.invoke || methodName == Name.invokeExact) {
            return findMethodHandleIntrinsic(methodName, signature, InvokeGeneric);
        } else if (methodName == Name.invokeBasic) {
            return findMethodHandleIntrinsic(methodName, signature, InvokeBasic);
        } else if (methodName == Name.linkToInterface) {
            return findMethodHandleIntrinsic(methodName, signature, LinkToInterface);
        } else if (methodName == Name.linkToSpecial) {
            return findMethodHandleIntrinsic(methodName, signature, LinkToSpecial);
        } else if (methodName == Name.linkToStatic) {
            return findMethodHandleIntrinsic(methodName, signature, LinkToStatic);
        } else if (methodName == Name.linkToVirtual) {
            return findMethodHandleIntrinsic(methodName, signature, LinkToVirtual);
        }
        for (Method m : getDeclaredMethods()) {
            if (m.isNative() && m.isVarargs() && m.getName() == methodName) {
                // check signature?
                throw EspressoError.unimplemented("New method handle invoke method? " + methodName);
            }
        }
        return null;
    }

    private Method findMethodHandleIntrinsic(@SuppressWarnings("unused") Symbol<Name> methodName, Symbol<Signature> signature, MethodHandleIntrinsics.PolySigIntrinsics id) {
        if (id == InvokeGeneric) {
            return getMeta().invoke.findIntrinsic(signature, new Function<Method, EspressoBaseNode>() {
                // TODO(garcia) Create a whole new Node to handle MH invokes.
                @Override
                public EspressoBaseNode apply(Method method) {
                    // TODO(garcia) true access checks
                    ObjectKlass callerKlass = getMeta().Object;
                    StaticObject appendixBox = new StaticObject(getMeta().Object_array, new Object[1]);
                    StaticObject memberName = (StaticObject) getMeta().linkMethod.invokeDirect(
                                    null,
                                    callerKlass.mirror(), (int) REF_invokeVirtual,
                                    getMeta().MethodHandle.mirror(), getMeta().toGuestString(methodName), getMeta().toGuestString(signature),
                                    appendixBox);
                    StaticObject appendix = appendixBox.get(0);
                    return new MHInvokeGenericNode(method, memberName, appendix);
                }
            }, id);
        } else if (id == InvokeBasic) {
            return getMeta().invokeBasic.findIntrinsic(signature, new Function<Method, EspressoBaseNode>() {
                @Override
                public EspressoBaseNode apply(Method method) {
                    return new MHInvokeBasicNode(method);
                }
            }, id);
        } else {
            Symbol<Signature> basicSignature = toBasic(getSignatures().parsed(signature), true, getSignatures());
            switch (id) {
                case LinkToInterface:
                    return findLinkToIntrinsic(getMeta().linkToInterface, basicSignature, id);
                case LinkToSpecial:
                    return findLinkToIntrinsic(getMeta().linkToSpecial, basicSignature, id);
                case LinkToStatic:
                    return findLinkToIntrinsic(getMeta().linkToStatic, basicSignature, id);
                case LinkToVirtual:
                    return findLinkToIntrinsic(getMeta().linkToVirtual, basicSignature, id);
                default:
                    throw EspressoError.shouldNotReachHere();
            }
        }
    }

    private static Method findLinkToIntrinsic(Method m, Symbol<Signature> signature, MethodHandleIntrinsics.PolySigIntrinsics id) {
        return m.findIntrinsic(signature, new Function<Method, EspressoBaseNode>() {
            @Override
            public EspressoBaseNode apply(Method method) {
                return MHLinkToNode.create(method, id);
            }
        }, id);
    }

    @Override
    public final int getModifiers() {
        return getFlags() & Constants.JVM_RECOGNIZED_CLASS_MODIFIERS;
    }

    protected abstract int getFlags();

    public final StaticObject allocateInstance() {
        return InterpreterToVM.newObject(this);
    }

    // TODO(garcia) Symbolify package ?
    @CompilationFinal private String runtimePackage;

    public final String getRuntimePackage() {
        String pkg = runtimePackage;
        if (runtimePackage == null) {
            CompilerDirectives.transferToInterpreterAndInvalidate();
            assert !isArray();
            pkg = Types.getRuntimePackage(getType());
            assert !pkg.endsWith(";");
            runtimePackage = pkg;
        }
        return pkg;
    }

    public Symbol<Name> getName() {
        return name;
    }

    public boolean sameRuntimePackage(Klass other) {
        return this.getDefiningClassLoader() == other.getDefiningClassLoader() && this.getRuntimePackage().equals(other.getRuntimePackage());
    }
}