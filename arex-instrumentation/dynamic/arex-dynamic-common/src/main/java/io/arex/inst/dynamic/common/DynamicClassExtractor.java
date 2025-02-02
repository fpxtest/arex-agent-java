package io.arex.inst.dynamic.common;

import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import io.arex.agent.bootstrap.model.MockResult;
import io.arex.agent.bootstrap.model.MockStrategyEnum;
import io.arex.agent.bootstrap.model.Mocker;
import io.arex.agent.bootstrap.util.ArrayUtils;
import io.arex.agent.bootstrap.util.StringUtil;
import io.arex.inst.serializer.ProtoJsonSerializer;
import io.arex.inst.dynamic.common.listener.ListenableFutureAdapter;
import io.arex.inst.dynamic.common.listener.ResponseConsumer;
import io.arex.inst.runtime.config.Config;
import io.arex.inst.runtime.context.ArexContext;
import io.arex.inst.runtime.context.ContextManager;
import io.arex.inst.runtime.model.DynamicClassEntity;
import io.arex.inst.runtime.serializer.Serializer;
import io.arex.inst.runtime.util.IgnoreUtils;
import io.arex.inst.runtime.log.LogManager;
import io.arex.inst.runtime.util.MockUtils;
import io.arex.inst.runtime.util.TypeUtil;
import java.lang.reflect.Array;
import java.lang.reflect.Method;
import java.util.Collection;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;

public class DynamicClassExtractor {
    private static final int RESULT_SIZE_MAX = Integer.parseInt(System.getProperty("arex.dynamic.result.size.limit", "1000"));
    private static final String SERIALIZER = "gson";
    private static final String PROTOCOL_BUFFERS = "protobuf";
    private static final String LISTENABLE_FUTURE = "com.google.common.util.concurrent.ListenableFuture";
    private static final String COMPLETABLE_FUTURE = "java.util.concurrent.CompletableFuture";
    private static final String PROTOBUF_PACKAGE_NAME = "com.google.protobuf";
    private static final String NEED_RECORD_TITLE = "dynamic.needRecord";
    private static final String NEED_REPLAY_TITLE = "dynamic.needReplay";
    private final String clazzName;
    private final String methodName;
    private final String methodKey;
    private String serializedResult;
    private Object result;
    private String resultClazz;
    private String methodSignatureKey;
    private final String methodReturnType;
    private int methodSignatureKeyHash;
    private final Class<?> actualType;
    private final Object[] args;
    private final String dynamicSignature;

    public DynamicClassExtractor(Method method, Object[] args, String keyExpression, Class<?> actualType) {
        this.clazzName = method.getDeclaringClass().getName();
        this.methodName = method.getName();
        this.args = args;
        this.dynamicSignature = getDynamicEntitySignature();
        this.methodKey = buildMethodKey(method, args, keyExpression);
        this.methodReturnType = TypeUtil.getName(method.getReturnType());
        this.actualType = actualType;
    }

    public DynamicClassExtractor(Method method, Object[] args) {
        this.clazzName = method.getDeclaringClass().getName();
        this.methodName = method.getName();
        this.args = args;
        this.dynamicSignature = getDynamicEntitySignature();
        this.methodKey = buildMethodKey(method, args);
        this.methodReturnType = TypeUtil.getName(method.getReturnType());
        this.actualType = null;
    }
    public void recordResponse(Object response) {
        if (IgnoreUtils.invalidOperation(dynamicSignature)) {
            LogManager.warn(NEED_RECORD_TITLE,
                    StringUtil.format("do not record invalid operation: %s, can not serialize args or response", dynamicSignature));
            return;
        }
        if (response instanceof Future<?>) {
            this.setFutureResponse((Future<?>) response);
            return;
        }
        this.result = response;
        if (needRecord()) {
            this.resultClazz = buildResultClazz(TypeUtil.getName(response));
            Mocker mocker = makeMocker();
            if (isProtobufObject(response)) {
                mocker.getTargetResponse().setAttribute("Format", PROTOCOL_BUFFERS);
                this.serializedResult = ProtoJsonSerializer.getInstance().serialize(this.result);
            } else {
                this.serializedResult = serialize(this.result);
            }
            mocker.getTargetResponse().setBody(this.serializedResult);
            MockUtils.recordMocker(mocker);
            cacheMethodSignature();
        }
    }

    private boolean isProtobufObject(Object result) {
        if (result == null) {
            return false;
        }
        if (result instanceof Collection<?>) {
            Collection<?> collection = (Collection<?>) result;
            if (collection.isEmpty()) {
                return false;
            }
            return isProtobufObject(collection.iterator().next());
        }
        Class<?> clazz = result.getClass();
        if (clazz.getSuperclass() == null) {
            return false;
        }
        return PROTOBUF_PACKAGE_NAME.equals(clazz.getSuperclass().getPackage().getName());
    }

    public MockResult replay() {
        if (IgnoreUtils.invalidOperation(dynamicSignature)) {
            LogManager.warn(NEED_REPLAY_TITLE,
                    StringUtil.format("do not replay invalid operation: %s, can not serialize args or response", dynamicSignature));
            return MockResult.IGNORE_MOCK_RESULT;
        }
        String key = buildCacheKey();
        Map<String, Object> cachedReplayResultMap = ContextManager.currentContext()
                .getCachedReplayResultMap();
        Object replayResult = null;
        // First get replay result from cache
        if (key != null) {
            replayResult = cachedReplayResultMap.get(key);
        }

        // If not in cache, get replay result from mock server
        if (replayResult == null) {
            Mocker replayMocker = MockUtils.replayMocker(makeMocker(), MockStrategyEnum.FIND_LAST);
            if (MockUtils.checkResponseMocker(replayMocker)) {
                String typeName = replayMocker.getTargetResponse().getType();
                replayResult = deserializeResult(replayMocker, typeName);
            }
            replayResult = restoreResponse(replayResult);
            // no key no cache, no parameter methods may return different values
            if (key != null && replayResult != null) {
                cachedReplayResultMap.put(key, replayResult);
            }
        }
        boolean ignoreMockResult = IgnoreUtils.ignoreMockResult(clazzName, methodName);
        return MockResult.success(ignoreMockResult, replayResult);
    }

    private Object deserializeResult(Mocker replayMocker, String typeName) {
        if (PROTOCOL_BUFFERS.equals(replayMocker.getTargetResponse().getAttribute("Format"))) {
            return ProtoJsonSerializer.getInstance().deserialize(replayMocker.getTargetResponse().getBody(),
                    TypeUtil.forName(typeName));
        }
        return Serializer.deserialize(replayMocker.getTargetResponse().getBody(), typeName, SERIALIZER);
    }

    void setFutureResponse(Future<?> result) {
        if (result instanceof CompletableFuture) {
            ((CompletableFuture<?>) result).whenComplete(new ResponseConsumer(this));
            return;
        }

        // Compatible with not import Guava
        if (LISTENABLE_FUTURE.equals(methodReturnType)) {
            ListenableFutureAdapter.addCallBack((ListenableFuture<?>) result, this);
        }
    }

    String buildResultClazz(String resultClazz) {
        if (StringUtil.isEmpty(resultClazz) || resultClazz.contains(TypeUtil.HORIZONTAL_LINE_STR)) {
            return resultClazz;
        }

        // @ArexMock actualType
        if (actualType != null && Object.class != actualType) {
            return resultClazz + TypeUtil.HORIZONTAL_LINE + actualType.getName();
        }

        if (Config.get() == null || Config.get().getDynamicClassSignatureMap().isEmpty()) {
            return resultClazz;
        }

        DynamicClassEntity dynamicEntity = Config.get().getDynamicEntity(dynamicSignature);

        if (dynamicEntity == null || StringUtil.isEmpty(dynamicEntity.getActualType())) {
            return resultClazz;
        }

        return resultClazz + TypeUtil.HORIZONTAL_LINE + dynamicEntity.getActualType();
    }

    String buildMethodKey(Method method, Object[] args, String keyExpression) {
        if (ArrayUtils.isEmpty(args)) {
            return null;
        }

        String key = ExpressionParseUtil.generateKey(method, args, keyExpression);
        if (key != null) {
            return key;
        }

        return serialize(args);
    }

    String buildMethodKey(Method method, Object[] args) {
        if (ArrayUtils.isEmpty(args)) {
            return null;
        }

        if (Config.get() == null || Config.get().getDynamicClassSignatureMap().isEmpty()) {
            return serialize(args);
        }

        DynamicClassEntity dynamicEntity = Config.get().getDynamicEntity(dynamicSignature);
        if (dynamicEntity == null || StringUtil.isEmpty(dynamicEntity.getAdditionalSignature())) {
            return serialize(args);
        }

        String keyExpression = ExpressionParseUtil.replaceToExpression(method, dynamicEntity.getAdditionalSignature());

        return buildMethodKey(method, args, keyExpression);
    }

    private String getDynamicEntitySignature() {
        if (ArrayUtils.isEmpty(this.args)) {
            return clazzName + methodName;
        }

        return clazzName + methodName + args.length;
    }

    private Mocker makeMocker() {
        Mocker mocker = MockUtils.createDynamicClass(this.clazzName, this.methodName);
        mocker.getTargetRequest().setBody(this.methodKey);
        mocker.getTargetResponse().setBody(this.serializedResult);
        mocker.getTargetResponse().setType(this.resultClazz);
        return mocker;
    }

    Object restoreResponse(Object result) {
        if (LISTENABLE_FUTURE.equals(this.methodReturnType)) {
            if (result instanceof Throwable) {
                return Futures.immediateFailedFuture((Throwable) result);
            }
            return Futures.immediateFuture(result);
        }

        if (COMPLETABLE_FUTURE.equals(this.methodReturnType)) {
            CompletableFuture<Object> completableFuture = new CompletableFuture<>();
            if (result instanceof Throwable) {
                completableFuture.completeExceptionally((Throwable) result);
            } else {
                completableFuture.complete(result);
            }

            return completableFuture;
        }

        return result;
    }

    private boolean needRecord() {
        /*
         * Judge whether the hash value of the method signature has been recorded to avoid repeated recording.
         * The nonparametric method may return different results and needs to be recorded
         * */
        ArexContext context = ContextManager.currentContext();
        if (context != null && methodKey != null) {
            this.methodSignatureKey = buildDuplicateMethodKey();
            this.methodSignatureKeyHash = StringUtil.encodeAndHash(methodSignatureKey);
            if (context.getMethodSignatureHashList().contains(methodSignatureKeyHash)) {
                if (Config.get().isEnableDebug()) {
                    LogManager.warn(NEED_RECORD_TITLE,
                            StringUtil.format("do not record method, cuz exist same method signature: %s", methodSignatureKey));
                }
                return false;
            }
        }

        if (result == null || result instanceof Throwable) {
            return true;
        }

        try {
            int size = 0;
            if (result instanceof Collection<?>) {
                size = ((Collection<?>) result).size();
            } else if (result instanceof Map<?, ?>) {
                size = ((Map<?, ?>) result).size();
            } else if (result.getClass().isArray()) {
                size = Array.getLength(result);
            }
            if (size > RESULT_SIZE_MAX) {
                LogManager.warn(NEED_RECORD_TITLE,
                        StringUtil.format("do not record method, cuz result size:%s > max limit: %s, method info: %s",
                                String.valueOf(size), String.valueOf(RESULT_SIZE_MAX), methodSignatureKey));
                return false;
            }
        } catch (Throwable e) {
            LogManager.warn(NEED_RECORD_TITLE, e);
        }
        return true;
    }

    private String buildDuplicateMethodKey() {
        if (Objects.isNull(result)) {
            return String.format("%s_%s_%s_no_result", clazzName, methodName, methodKey);
        }
        return String.format("%s_%s_%s_has_result_%s", clazzName, methodName, methodKey, getResultKey());
    }

    private String getResultKey() {
        String resultClassName = result.getClass().getName();
        if (result instanceof Collection<?>) {
            return resultClassName + ((Collection<?>) result).size();
        }
        if (result instanceof Map<?, ?>) {
            return resultClassName + ((Map<?, ?>) result).size();
        }
        if (result.getClass().isArray()) {
            return resultClassName + Array.getLength(result);
        }
        return resultClassName;
    }

    /**
     * cache dynamic method with hashcode of signature,in order to filter out duplicate next record
     */
    private void cacheMethodSignature() {
        ArexContext context = ContextManager.currentContext();
        if (context != null && this.methodKey != null && this.methodSignatureKey != null) {
            context.getMethodSignatureHashList().add(this.methodSignatureKeyHash);
        }
    }

    private String buildCacheKey() {
        if (StringUtil.isNotEmpty(this.methodKey)) {
            return String.format("%s_%s_%s", this.clazzName, this.methodName, this.methodKey);
        }
        return null;
    }

    public String getSerializedResult() {
        return serializedResult;
    }

    private String serialize(Object object) {
        if (IgnoreUtils.invalidOperation(dynamicSignature)) {
            return null;
        }
        try {
            return Serializer.serializeWithException(object, SERIALIZER);
        } catch (Throwable ex) {
            IgnoreUtils.addInvalidOperation(dynamicSignature);
            LogManager.warn("serializeWithException", StringUtil.format("can not serialize object: %s, cause: %s", TypeUtil.errorSerializeToString(object), ex.toString()));
            return null;
        }
    }
}
