package com.indore.pathome.spaces.support;

import com.indore.pathome.spaces.repository.PropertyMediaAssetRepository;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** One-shot test seam after listing/rent reads and immediately before a real media query. */
final class OgV63MediaCheckpoint {
    interface Gate {
        void beforeMediaRead() throws Exception;
    }

    private static final AtomicReference<Gate> ACTIVE_GATE = new AtomicReference<>();

    private OgV63MediaCheckpoint() {}

    static void install(Gate gate) {
        if (!ACTIVE_GATE.compareAndSet(null, gate)) {
            throw new IllegalStateException("A media checkpoint is already installed");
        }
    }

    static void clear() {
        ACTIVE_GATE.set(null);
    }

    static PropertyMediaAssetRepository decorate(PropertyMediaAssetRepository delegate) {
        return (PropertyMediaAssetRepository) Proxy.newProxyInstance(
                PropertyMediaAssetRepository.class.getClassLoader(),
                new Class<?>[]{PropertyMediaAssetRepository.class},
                new Handler(delegate));
    }

    private record Handler(PropertyMediaAssetRepository delegate) implements InvocationHandler {
        @Override
        public Object invoke(Object proxy, Method method, Object[] arguments) throws Throwable {
            if ((method.getName().equals("findPublicCoverCandidates")
                    || method.getName().equals("findByListingIdOrderByUploadedAtDesc"))
                    && !method.isDefault()) {
                Gate gate = ACTIVE_GATE.get();
                if (gate != null && ACTIVE_GATE.compareAndSet(gate, null)) {
                    gate.beforeMediaRead();
                }
            }
            try {
                return method.invoke(delegate, arguments);
            } catch (InvocationTargetException exception) {
                throw exception.getCause();
            }
        }
    }
}
