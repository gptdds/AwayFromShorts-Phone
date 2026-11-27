package com.afs.test;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;

public final class FailingContext extends ContextWrapper {
    private final String file;
    private boolean failed;

    public FailingContext(Context context,String file) { super(context); this.file = file; }

    public SharedPreferences getSharedPreferences(String name,int mode) {
        SharedPreferences original = super.getSharedPreferences(name,mode);
        if (!file.equals(name)) return original;
        return (SharedPreferences) Proxy.newProxyInstance(SharedPreferences.class.getClassLoader(),new Class<?>[]{SharedPreferences.class},
                (proxy,method,args) -> {
                    if (!method.getName().equals("edit")) return method.invoke(original,args);
                    SharedPreferences.Editor edit = original.edit();
                    return Proxy.newProxyInstance(SharedPreferences.Editor.class.getClassLoader(),new Class<?>[]{SharedPreferences.Editor.class},
                            (editorProxy,editorMethod,editorArgs) -> {
                                if (editorMethod.getName().equals("commit") && !failed) { failed = true; return false; }
                                try {
                                    Object value = editorMethod.invoke(edit,editorArgs);
                                    return value instanceof SharedPreferences.Editor ? editorProxy : value;
                                } catch (InvocationTargetException error) { throw error.getCause(); }
                            });
                });
    }
}
