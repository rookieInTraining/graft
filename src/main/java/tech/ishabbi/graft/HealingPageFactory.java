package tech.ishabbi.graft;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Modifier;

/**
 * Drop-in counterpart of Selenium's {@code PageFactory} for {@link Element}-annotated fields.
 *
 * <p>Fields without {@code @Element} are left untouched, so you can run Selenium's
 * {@code PageFactory.initElements} (or Appium's {@code AppiumFieldDecorator}) on the same object
 * for the non-healing fields and this one for the healing fields.
 */
public final class HealingPageFactory {

    private HealingPageFactory() {}

    /** Instantiates {@code pageClass} (no-arg ctor, or a ctor taking the native driver) and wires it. */
    public static <T> T create(Class<T> pageClass, Healer healer, Object nativeDriver) {
        T page = instantiate(pageClass, nativeDriver);
        initElements(page, healer);
        return page;
    }

    /** Assigns a self-healing proxy to every {@code @Element} field of {@code page} and its superclasses. */
    public static void initElements(Object page, Healer healer) {
        Class<?> type = page.getClass();
        while (type != null && type != Object.class) {
            for (Field field : type.getDeclaredFields()) {
                if (!field.isAnnotationPresent(Element.class)) continue;
                if (Modifier.isStatic(field.getModifiers()) || Modifier.isFinal(field.getModifiers())) {
                    throw new IllegalStateException("@Element field " + type.getSimpleName() + "."
                            + field.getName() + " must be a non-static, non-final instance field");
                }
                ElementSpec spec = ElementSpec.of(page.getClass(), field);
                Object proxy = healer.createElement(spec, field.getType());
                try {
                    field.setAccessible(true);
                    field.set(page, proxy);
                } catch (IllegalAccessException e) {
                    throw new IllegalStateException("Cannot inject " + spec.displayName(), e);
                }
            }
            type = type.getSuperclass();
        }
    }

    private static <T> T instantiate(Class<T> pageClass, Object nativeDriver) {
        try {
            if (nativeDriver != null) {
                for (Constructor<?> c : pageClass.getDeclaredConstructors()) {
                    if (c.getParameterCount() == 1 && c.getParameterTypes()[0].isInstance(nativeDriver)) {
                        c.setAccessible(true);
                        return pageClass.cast(c.newInstance(nativeDriver));
                    }
                }
            }
            Constructor<T> noArg = pageClass.getDeclaredConstructor();
            noArg.setAccessible(true);
            return noArg.newInstance();
        } catch (NoSuchMethodException e) {
            throw new IllegalArgumentException(pageClass.getName()
                    + " needs a no-arg constructor or one taking the native driver/page", e);
        } catch (InstantiationException | IllegalAccessException | InvocationTargetException e) {
            throw new IllegalStateException("Could not instantiate " + pageClass.getName(), e);
        }
    }
}
