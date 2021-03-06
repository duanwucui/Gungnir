package io.github.chinalhr.gungnir_rpc.netchannel.client;

import io.github.chinalhr.gungnir_rpc.exception.GRpcRuntimeException;
import io.github.chinalhr.gungnir_rpc.lock.pool.GRedisPool;
import io.github.chinalhr.gungnir_rpc.netchannel.client.future.GResponseCallback;
import io.github.chinalhr.gungnir_rpc.netchannel.client.pool.NettyChannelPoolFactory;
import io.github.chinalhr.gungnir_rpc.netchannel.config.GungnirClientConfig;
import io.github.chinalhr.gungnir_rpc.protocol.ConsumerService;
import io.github.chinalhr.gungnir_rpc.protocol.GRequest;
import io.github.chinalhr.gungnir_rpc.protocol.GResponse;
import io.github.chinalhr.gungnir_rpc.protocol.ProviderService;
import io.github.chinalhr.gungnir_rpc.register.IRegisterCenter;
import io.github.chinalhr.gungnir_rpc.register.RegisterCenterObserver;
import io.github.chinalhr.gungnir_rpc.register.zk.RegisterCenter;
import io.github.chinalhr.gungnir_rpc.serializer.ISerializer;
import io.github.chinalhr.gungnir_rpc.threadpool.GungnirClientThreadPoolManager;
import io.github.chinalhr.gungnir_rpc.utils.GeneralUtils;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.FactoryBean;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.util.StringUtils;

import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;

/**
 * @Author : ChinaLHR
 * @Date : Create in 14:13 2018/4/26
 * @Email : 13435500980@163.com
 */
public class GungnirClientProxy extends GungnirClientConfig implements FactoryBean<Object>, InitializingBean,DisposableBean {

    private IRegisterCenter registerCenter;
    private String serviceAddress;
    private RegisterObserver observer = new RegisterObserver();

    public GungnirClientProxy() {
    }

    public GungnirClientProxy(Class<?> iclass, String version, ISerializer serializer, long timeoutMillis,String groupName) {
        this.iclass = iclass;
        this.version = version;
        this.serializer = serializer;
        this.timeoutMillis = timeoutMillis;
        this.groupName = groupName;
        try {
            afterPropertiesSet();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * @return
     * @throws Exception
     */
    @Override
    public Object getObject() throws Exception {

        return Proxy.newProxyInstance(iclass.getClassLoader(), new Class[]{iclass}, (proxy, method, args) -> {
            //封装GRequest
            GRequest request = new GRequest();
            request.setClassName(method.getDeclaringClass().getName());
            request.setCreateMillisTime(System.currentTimeMillis());
            request.setRequestID(GeneralUtils.getUUID());
            request.setMethodName(method.getName());
            request.setParameterTypes(method.getParameterTypes());
            request.setParameters(args);
            request.setVersion(version);
            request.setGroupName(groupName);

            if (registerCenter != null) {
                String serviceName = iclass.getName();
                if (!StringUtils.isEmpty(version)) {
                    serviceName += "-" + version;
                }
                Map<String, Map<String, List<ProviderService>>> providerServiceMap = registerCenter.getProviderServiceMap();
                List<ProviderService> providerServices = providerServiceMap.get(groupName).get(serviceName);
                //根据负载均衡策略获取providerService
                ProviderService providerService = loadBalance.selectProviderService(providerServices);
                serviceAddress = providerService.getAddress();
                LOGGER.debug("GungnirClientProxy discover serviceAddress={}", serviceAddress);
            } else {
                LOGGER.error("GungnirClientProxy serviceDiscovery is null");
                throw new GRpcRuntimeException("GungnirClientProxy serviceDiscovery is empty");
            }

            ExecutorService fixedThreadPool = GungnirClientThreadPoolManager.getResponseCallThreadPool();
            Future<GResponse> future = fixedThreadPool.submit(GResponseCallback.build(serviceAddress, serializer, timeoutMillis, request));
            GResponse response = future.get(timeoutMillis, TimeUnit.MILLISECONDS);

            if (response == null) {
                LOGGER.error("GungnirClientProxy Get GResponse Is Null");
                throw new GRpcRuntimeException("GungnirClientProxy Get GResponse Is Null");
            }

            if (response.getError() != null) {
                LOGGER.error("GungnirClientProxy Get GResponse Error:{}", response.getError().getLocalizedMessage());
                throw response.getError();
            } else {
                return response.getResult();
            }
        });
    }

    @Override
    public Class<?> getObjectType() {
        return iclass;
    }

    @Override
    public boolean isSingleton() {
        return false;
    }

    @Override
    public void afterPropertiesSet() throws Exception {
        registerCenter = RegisterCenter.getInstance();
        registerCenter.initProviderMap();
        GRedisPool.initPool();
        registerCenter.attach(groupName,observer);
        //进行消费者注册
        String serviceName = iclass.getName();
        if (!StringUtils.isEmpty(version)) {
            serviceName += "-" + version;
        }

        ConsumerService consumerService = new ConsumerService();
        consumerService.setIp(GeneralUtils.getHostAddress());
        consumerService.setServiceName(serviceName);
        consumerService.setGroupName(groupName);
        registerCenter.registerConsumer(consumerService);
    }

    @Override
    public void destroy() throws Exception {
        registerCenter.detach(groupName,observer);
    }

    /**
     * 注册中心观察者
     */
    class RegisterObserver implements RegisterCenterObserver{

        @Override
        public void updateProviderService() {
            NettyChannelPoolFactory.getInstance().cleanChannelPoolMap();
        }
    }
}

