package com.wafflestudio.snutt.parity.shim;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.cglib.proxy.Enhancer;
import org.springframework.cglib.proxy.Factory;
import org.springframework.cglib.proxy.MethodInterceptor;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.objenesis.ObjenesisStd;
import org.springframework.util.ClassUtils;

/**
 * v1 서버 컨테이너 안에서만 쓰는 shim. v1 jar는 고치지 않고 {@code context.initializer.classes}로 끼워 넣는다.
 *
 * <p>v1은 기동할 때 OCI Object Storage namespace를 네트워크로 조회하고(StorageClient @PostConstruct), FCM 서비스
 * 계정을 파싱한다. v2 테스트가 PushClient·MailClient·UploadUriIssuer를 빈 mock으로 바꾸는 것과 같은 SDK 경계를 v1에서도
 * 끊는다. {@code parity.stub-bean-classes}에 적힌 클래스의 빈은 생성자를 거치지 않은 무동작 객체로 바뀌고,
 * {@code parity.lazy-bean-names}에 적힌 빈은 쓰는 곳이 없으면 만들어지지 않게 lazy로 바뀐다.
 */
public class V1ShimInitializer implements ApplicationContextInitializer<ConfigurableApplicationContext> {
    static final String STUB_STRING = "https://parity.invalid/v1-stub";

    @Override
    public void initialize(ConfigurableApplicationContext context) {
        Set<String> stubClasses = split(context.getEnvironment().getProperty("parity.stub-bean-classes", ""));
        Set<String> lazyBeans = split(context.getEnvironment().getProperty("parity.lazy-bean-names", ""));
        context.addBeanFactoryPostProcessor(new StubPostProcessor(stubClasses, lazyBeans));
    }

    private static Set<String> split(String value) {
        return Arrays.stream(value.split(",")).map(String::trim).filter(s -> !s.isEmpty()).collect(Collectors.toSet());
    }

    /** 컴포넌트 스캔이 끝난 뒤(postProcessBeanFactory 단계), 인스턴스를 만들기 전에 정의를 바꾼다. */
    static final class StubPostProcessor implements BeanFactoryPostProcessor {
        private final Set<String> stubClasses;
        private final Set<String> lazyBeans;

        StubPostProcessor(Set<String> stubClasses, Set<String> lazyBeans) {
            this.stubClasses = stubClasses;
            this.lazyBeans = lazyBeans;
        }

        @Override
        public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) {
            BeanDefinitionRegistry registry = (BeanDefinitionRegistry) beanFactory;
            for (String name : beanFactory.getBeanDefinitionNames()) {
                BeanDefinition definition = beanFactory.getBeanDefinition(name);
                if (lazyBeans.contains(name)) {
                    definition.setLazyInit(true);
                }
                String className = definition.getBeanClassName();
                if (className == null || !stubClasses.contains(className)) {
                    continue;
                }
                Class<?> type = ClassUtils.resolveClassName(className, beanFactory.getBeanClassLoader());
                registry.removeBeanDefinition(name);
                registry.registerBeanDefinition(name, stubDefinition(type));
            }
        }

        @SuppressWarnings({"unchecked", "rawtypes"})
        private static RootBeanDefinition stubDefinition(Class<?> type) {
            return new RootBeanDefinition((Class) type, () -> createStub(type));
        }
    }

    static Object createStub(Class<?> type) {
        if (Modifier.isFinal(type.getModifiers())) {
            return Proxy.newProxyInstance(type.getClassLoader(), type.getInterfaces(), (proxy, method, args) -> answer(proxy, method, args));
        }
        Enhancer enhancer = new Enhancer();
        enhancer.setSuperclass(type);
        enhancer.setClassLoader(type.getClassLoader());
        enhancer.setUseFactory(true);
        enhancer.setCallbackType(MethodInterceptor.class);
        Class<?> proxyClass = enhancer.createClass();
        Factory instance = (Factory) new ObjenesisStd().newInstance(proxyClass);
        instance.setCallback(0, (MethodInterceptor) (obj, method, args, methodProxy) -> answer(obj, method, args));
        return instance;
    }

    private static Object answer(Object self, Method method, Object[] args) {
        switch (method.getName()) {
            case "equals":
                return args != null && args.length == 1 && self == args[0];
            case "hashCode":
                return System.identityHashCode(self);
            case "toString":
                return "parity-stub(" + method.getDeclaringClass().getSimpleName() + ")";
            default:
                break;
        }
        Class<?> returnType = method.getReturnType();
        if (returnType == String.class) {
            return STUB_STRING;
        }
        if (returnType == boolean.class) {
            return false;
        }
        if (returnType == int.class || returnType == long.class || returnType == short.class || returnType == byte.class) {
            return ClassUtils.resolvePrimitiveIfNecessary(returnType) == Long.class ? (Object) 0L : (Object) 0;
        }
        if (returnType == double.class || returnType == float.class) {
            return 0.0;
        }
        return null;
    }
}
