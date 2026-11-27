package com.afs.guard.style;

import android.os.Handler;
import android.os.Looper;
import android.view.FrameMetrics;
import android.view.Window;

/** API 24+ observer scoped to a visible window; no frame loop or background polling. */
public final class FrameMetricsWatcher implements AutoCloseable {
    private Window window;
    private long sampleCount;
    public long sampleCount(){return sampleCount;}
    private final Window.OnFrameMetricsAvailableListener listener=(w,metrics,dropped)->{
        if(metrics.getMetric(FrameMetrics.FIRST_DRAW_FRAME)==1)return;
        long nanos=metrics.getMetric(FrameMetrics.TOTAL_DURATION);
        if(nanos>0){
            sampleCount++;
            android.view.Display display=w.getDecorView().getDisplay();
            long budget=FrameBudget.intervalNanos(display==null?60:display.getRefreshRate());
            EffectQualityManager.reportFrameNanos(nanos,budget);
        }
    };
    public FrameMetricsWatcher(Window window){
        this.window=window;
        window.addOnFrameMetricsAvailableListener(listener,new Handler(Looper.getMainLooper()));
    }
    public void close(){if(window!=null){window.removeOnFrameMetricsAvailableListener(listener);window=null;}}
}
