package butterknife;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.res.ColorStateList;
import android.content.res.Resources;
import android.graphics.drawable.Drawable;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.annotation.UiThread;
import android.support.v4.content.ContextCompat;
import android.util.Log;
import android.view.View;
import butterknife.internal.Constants;
import butterknife.internal.Utils;
import java.lang.annotation.Annotation;
import java.lang.reflect.AccessibleObject;
import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Member;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static java.lang.reflect.Modifier.PRIVATE;
import static java.lang.reflect.Modifier.PUBLIC;
import static java.lang.reflect.Modifier.STATIC;
import static java.util.Collections.singletonList;

public final class ButterKnife {
  private ButterKnife() {
    throw new AssertionError();
  }

  private static final String TAG = "ButterKnife";
  private static boolean debug = false;

  /** Control whether debug logging is enabled. */
  public static void setDebug(boolean debug) {
    ButterKnife.debug = debug;
  }

  /**
   * BindView annotated fields and methods in the specified {@link Activity}. The current content
   * view is used as the view root.
   *
   * @param target Target activity for view binding.
   */
  @NonNull @UiThread
  public static Unbinder bind(@NonNull Activity target) {
    View sourceView = target.getWindow().getDecorView();
    return bind(target, sourceView);
  }

  /**
   * BindView annotated fields and methods in the specified {@link View}. The view and its children
   * are used as the view root.
   *
   * @param target Target view for view binding.
   */
  @NonNull @UiThread
  public static Unbinder bind(@NonNull View target) {
    return bind(target, target);
  }

  /**
   * BindView annotated fields and methods in the specified {@link Dialog}. The current content
   * view is used as the view root.
   *
   * @param target Target dialog for view binding.
   */
  @NonNull @UiThread
  public static Unbinder bind(@NonNull Dialog target) {
    View sourceView = target.getWindow().getDecorView();
    return bind(target, sourceView);
  }

  /**
   * BindView annotated fields and methods in the specified {@code target} using the {@code source}
   * {@link Activity} as the view root.
   *
   * @param target Target class for view binding.
   * @param source Activity on which IDs will be looked up.
   */
  @NonNull @UiThread
  public static Unbinder bind(@NonNull Object target, @NonNull Activity source) {
    View sourceView = source.getWindow().getDecorView();
    return bind(target, sourceView);
  }

  /**
   * BindView annotated fields and methods in the specified {@code target} using the {@code source}
   * {@link Dialog} as the view root.
   *
   * @param target Target class for view binding.
   * @param source Dialog on which IDs will be looked up.
   */
  @NonNull @UiThread
  public static Unbinder bind(@NonNull Object target, @NonNull Dialog source) {
    View sourceView = source.getWindow().getDecorView();
    return bind(target, sourceView);
  }

  /**
   * BindView annotated fields and methods in the specified {@code target} using the {@code source}
   * {@link View} as the view root.
   *
   * @param target Target class for view binding.
   * @param source View root on which IDs will be looked up.
   */
  @NonNull @UiThread
  public static Unbinder bind(@NonNull Object target, @NonNull View source) {
    List<Unbinder> unbinders = new ArrayList<>();
    Class<?> targetClass = target.getClass();
    if ((targetClass.getModifiers() & PRIVATE) != 0) {
      throw new IllegalArgumentException(targetClass.getName() + " must not be private.");
    }

    while (true) {
      String clsName = targetClass.getName();
      if (clsName.startsWith("android.") || clsName.startsWith("java.")) {
        break;
      }

      for (Field field : targetClass.getDeclaredFields()) {
        Unbinder unbinder = parseBindView(target, field, source);
        if (unbinder == null) unbinder = parseBindViews(target, field, source);
        if (unbinder == null) unbinder = parseBindColor(target, field, source);
        if (unbinder == null) unbinder = parseBindDimen(target, field, source);
        if (unbinder == null) unbinder = parseBindDrawable(target, field, source);
        if (unbinder == null) unbinder = parseBindString(target, field, source);

        if (unbinder != null) {
          unbinders.add(unbinder);
        }
      }
      for (Method method : targetClass.getDeclaredMethods()) {
        Unbinder unbinder = parseOnClick(target, method, source);
        if (unbinder == null) unbinder = parseOnLongClick(target, method, source);

        if (unbinder != null) {
          unbinders.add(unbinder);
        }
      }
      targetClass = targetClass.getSuperclass();
    }

    if (unbinders.isEmpty()) {
      if (debug) Log.d(TAG, "MISS: Reached framework class. Abandoning search.");
      return Unbinder.EMPTY;
    }

    if (debug) Log.d(TAG, "HIT: Reflectively found " + unbinders.size() + " bindings.");
    return new CompositeUnbinder(unbinders);
  }

  private static @Nullable Unbinder parseBindView(Object target, Field field, View source) {
    BindView bindView = field.getAnnotation(BindView.class);
    if (bindView == null) {
      return null;
    }
    validateMember(field);

    int id = bindView.value();
    boolean isRequired = isRequired(field);
    Class<?> viewClass = field.getType();
    String who = "field '" + field.getName() + "'";
    Object view;
    if (isRequired) {
      view = Utils.findRequiredViewAsType(source, id, who, viewClass);
    } else {
      view = Utils.findOptionalViewAsType(source, id, who, viewClass);
    }
    uncheckedSet(field, target, view);

    return new FieldUnbinder(target, field);
  }

  private static @Nullable Unbinder parseBindViews(Object target, Field field, View source) {
    BindViews bindViews = field.getAnnotation(BindViews.class);
    if (bindViews == null) {
      return null;
    }
    validateMember(field);

    Class<?> fieldClass = field.getType();
    Class<?> viewClass;
    boolean isArray = fieldClass.isArray();
    if (isArray) {
      viewClass = fieldClass.getComponentType();
    } else if (fieldClass == List.class) {
      Type fieldType = field.getGenericType();
      if (fieldType instanceof ParameterizedType) {
        Type viewType = ((ParameterizedType) fieldType).getActualTypeArguments()[0];
        // TODO real rawType impl!!!!
        viewClass = (Class<?>) viewType;
      } else {
        throw new IllegalStateException(); // TODO
      }
    } else {
      throw new IllegalStateException(); // TODO
    }

    int[] ids = bindViews.value();
    boolean isRequired = isRequired(field);
    List<Object> views = new ArrayList<>(ids.length);
    String who = "field '" + field.getName() + "'";
    for (int id : ids) {
      Object view;
      if (isRequired) {
        view = Utils.findRequiredViewAsType(source, id, who, viewClass);
      } else {
        view = Utils.findOptionalViewAsType(source, id, who, viewClass);
      }
      if (view != null) {
        views.add(view);
      }
    }

    Object value;
    if (isArray) {
      Object[] viewArray = (Object[]) Array.newInstance(viewClass, views.size());
      value = views.toArray(viewArray);
    } else {
      value = views;
    }

    uncheckedSet(field, target, value);
    return new FieldUnbinder(target, field);
  }

  private static @Nullable Unbinder parseBindColor(Object target, Field field, View source) {
    BindColor bindColor = field.getAnnotation(BindColor.class);
    if (bindColor == null) {
      return null;
    }
    validateMember(field);

    int id = bindColor.value();
    Context context = source.getContext();

    Object value;
    Class<?> fieldType = field.getType();
    if (fieldType == int.class) {
      value = ContextCompat.getColor(context, id);
    } else if (fieldType == ColorStateList.class) {
      value = ContextCompat.getColorStateList(context, id);
    } else {
      throw new IllegalStateException(); // TODO
    }
    uncheckedSet(field, target, value);

    return Unbinder.EMPTY;
  }

  private static @Nullable Unbinder parseBindDimen(Object target, Field field, View source) {
    BindDimen bindDimen = field.getAnnotation(BindDimen.class);
    if (bindDimen == null) {
      return null;
    }
    validateMember(field);

    int id = bindDimen.value();
    Resources resources = source.getContext().getResources();

    Class<?> fieldType = field.getType();
    Object value;
    if (fieldType == int.class) {
      value = resources.getDimensionPixelSize(id);
    } else if (fieldType == float.class) {
      value = resources.getDimension(id);
    } else {
      throw new IllegalStateException(); // TODO
    }
    uncheckedSet(field, target, value);

    return Unbinder.EMPTY;
  }

  private static @Nullable Unbinder parseBindDrawable(Object target, Field field, View source) {
    BindDrawable bindDrawable = field.getAnnotation(BindDrawable.class);
    if (bindDrawable == null) {
      return null;
    }
    validateMember(field);

    int id = bindDrawable.value();
    int tint = bindDrawable.tint();
    Context context = source.getContext();

    Class<?> fieldType = field.getType();
    Object value;
    if (fieldType == Drawable.class) {
      value = tint != Constants.NO_RES_ID
          ? Utils.getTintedDrawable(context, id, tint)
          : ContextCompat.getDrawable(context, id);
    } else {
      throw new IllegalStateException(); // TODO
    }
    uncheckedSet(field, target, value);

    return Unbinder.EMPTY;
  }

  private static @Nullable Unbinder parseBindString(Object target, Field field, View source) {
    BindString bindString = field.getAnnotation(BindString.class);
    if (bindString == null) {
      return null;
    }
    validateMember(field);

    Class<?> fieldType = field.getType();
    Object value;
    if (fieldType == String.class) {
      value = source.getContext().getString(bindString.value());
    } else {
      throw new IllegalStateException(); // TODO
    }
    uncheckedSet(field, target, value);

    return Unbinder.EMPTY;
  }

  private static @Nullable Unbinder parseOnClick(final Object target, final Method method,
      View source) {
    OnClick onClick = method.getAnnotation(OnClick.class);
    if (onClick == null) {
      return null;
    }
    validateMember(method);
    final Class<?>[] parameterTypes = method.getParameterTypes();
    // TODO validate parameter count (and types?)

    List<View> views = findViews(source, onClick.value(), isRequired(method), method.getName());

    ViewCollections.set(views, ON_CLICK, new View.OnClickListener() {
      @Override public void onClick(View v) {
        if (parameterTypes.length == 0) {
          uncheckedInvoke(method, target);
        } else {
          uncheckedInvoke(method, target, v);
        }
      }
    });

    return new ListenerUnbinder<>(views, ON_CLICK);
  }

  private static @Nullable Unbinder parseOnLongClick(final Object target, final Method method,
      View source) {
    OnLongClick onLongClick = method.getAnnotation(OnLongClick.class);
    if (onLongClick == null) {
      return null;
    }
    // TODO check is instance method
    validateMember(method);
    final Class<?>[] parameterTypes = method.getParameterTypes();
    // TODO validate parameter count (and types?)
    final Class<?> returnType = method.getReturnType();
    // TODO validate return type

    List<View> views = findViews(source, onLongClick.value(), isRequired(method), method.getName());

    ViewCollections.set(views, ON_LONG_CLICK, new View.OnLongClickListener() {
      @Override public boolean onLongClick(View v) {
        Object returnValue;
        if (parameterTypes.length == 0) {
          returnValue = uncheckedInvoke(method, target);
        } else {
          returnValue = uncheckedInvoke(method, target, v);
        }
        if (returnType != void.class) {
          return (boolean) returnValue;
        }
        return false;
      }
    });

    return new ListenerUnbinder<>(views, ON_LONG_CLICK);
  }

  private static List<View> findViews(View source, int[] ids, boolean isRequired, String name) {
    if (ids.length == 1 && ids[0] == View.NO_ID) {
      return singletonList(source);
    }

    String who = "method '" + name + "'";
    List<View> views = new ArrayList<>(ids.length);
    for (int id : ids) {
      if (isRequired) {
        views.add(Utils.findRequiredView(source, id, who));
      } else {
        View view = source.findViewById(id);
        if (view != null) {
          views.add(view);
        }
      }
    }
    return views;
  }

  private static <T extends AccessibleObject & Member> void validateMember(T object) {
    int modifiers = object.getModifiers();
    if ((modifiers & (PRIVATE | STATIC)) != 0) {
      throw new IllegalStateException(object.getDeclaringClass().getName()
          + "."
          + object.getName()
          + " must not be private or static");
    }
    if ((modifiers & PUBLIC) == 0) {
      object.setAccessible(true);
    }
  }

  private static boolean isRequired(Field field) {
    for (Annotation annotation : field.getAnnotations()) {
      if (annotation.getClass().getSimpleName().equals("Nullable")) {
        return false;
      }
    }
    return true;
  }

  private static boolean isRequired(Method method) {
    return method.getAnnotation(Optional.class) == null;
  }

  static void uncheckedSet(Field field, Object target, @Nullable Object value) {
    try {
      field.set(target, value);
    } catch (IllegalAccessException e) {
      throw new RuntimeException("Unable to assign " + value + " to " + field + " on " + target, e);
    }
  }

  private static Object uncheckedInvoke(Method method, Object target, Object... arguments) {
    Throwable cause;
    try {
      return method.invoke(target, arguments);
    } catch (IllegalAccessException e) {
      cause = e;
    } catch (InvocationTargetException e) {
      cause = e;
    }
    throw new RuntimeException(
        "Unable to invoke " + method + " on " + target + " with arguments "
            + Arrays.toString(arguments), cause);
  }

  private static final Setter<View, View.OnClickListener> ON_CLICK =
      new Setter<View, View.OnClickListener>() {
        @Override public void set(@NonNull View view, View.OnClickListener value, int index) {
          view.setOnClickListener(value);
        }
      };
  private static final Setter<View, View.OnLongClickListener> ON_LONG_CLICK =
      new Setter<View, View.OnLongClickListener>() {
        @Override public void set(@NonNull View view, View.OnLongClickListener value, int index) {
          view.setOnLongClickListener(value);
        }
      };
}
