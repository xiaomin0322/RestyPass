package com.github.df.restypass.spring.proxy;

import com.github.df.restypass.base.DefaultRestyPassFactory;
import com.github.df.restypass.base.RestyPassFactory;
import com.github.df.restypass.command.RestyCommandContext;
import com.github.df.restypass.executor.CommandExecutor;
import com.github.df.restypass.executor.FallbackExecutor;
import com.github.df.restypass.filter.CommandFilter;
import com.github.df.restypass.filter.CommandFilterContext;
import com.github.df.restypass.lb.server.ServerContext;
import com.github.df.restypass.util.CommonTools;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.FactoryBean;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.util.Assert;

import java.lang.reflect.Proxy;
import java.util.*;
import java.util.concurrent.locks.ReentrantLock;

/**
 * RestyService代理工厂类
 *
 * @author darren -fu
 */
@Data
@Slf4j
public class RestyProxyBeanFactory implements FactoryBean<Object>, InitializingBean, ApplicationContextAware {

    /**
     * 代理类 class
     */
    private Class<?> type;

    /**
     * RestyCommand容器
     */
    private RestyCommandContext restyCommandContext;

    /**
     * spring容器
     */
    private ApplicationContext applicationContext;

    /**
     * 服务实例容器
     */
    private ServerContext serverContext;

    /**
     * command执行器
     */
    private CommandExecutor commandExecutor;

    /**
     * 降级服务执行器
     */
    private FallbackExecutor fallbackExecutor;

    /**
     * 默认实现工厂类
     */
    private RestyPassFactory factory;

    /**
     * 过滤器容器
     */
    private CommandFilterContext commandFilterContext;

    /**
     * 是否完成初始化
     */
    private boolean inited = false;

    private ReentrantLock initLock = new ReentrantLock();

    @Override
    public Object getObject() throws Exception {
        return createProxy(type, restyCommandContext);
    }

    @Override
    public Class<?> getObjectType() {
        return type;
    }

    @Override
    public boolean isSingleton() {
        return true;
    }

    /**
     * Create proxy object.
     *
     * @param type                the type
     * @param restyCommandContext the resty command context
     * @return the object
     */
    protected Object createProxy(Class type, RestyCommandContext restyCommandContext) {

        if (!inited) {
            initLock.lock();
            try {
                if (!inited) {
                    // 初始化服务实例容器
                    this.serverContext = getBean(ServerContext.class);
                    if (serverContext instanceof ApplicationContextAware) {
                        ApplicationContextAware contextAware = (ApplicationContextAware) serverContext;
                        contextAware.setApplicationContext(this.applicationContext);
                    }
                    // 初始化command执行器
                    this.commandExecutor = getBean(CommandExecutor.class);
                    // 初始化降级服务执行器
                    this.fallbackExecutor = getBean(FallbackExecutor.class);

                    // 设置过滤器容器
                    this.commandFilterContext = new CommandFilterContext(getBeans(CommandFilter.class));
                    this.commandFilterContext.addFilterList(DefaultRestyPassFactory.INSTANCE.getCommandFilter());
                    // init done
                    inited = true;
                }
            } finally {
                initLock.unlock();
            }
        }

        Object proxy = null;
        try {
            RestyProxyInvokeHandler interfaceIvkHandler =
                    new RestyProxyInvokeHandler(restyCommandContext, commandExecutor, fallbackExecutor, serverContext, commandFilterContext);
            proxy = Proxy.newProxyInstance(type.getClassLoader(), new Class[]{type}, interfaceIvkHandler);
        } catch (Exception e) {
            e.printStackTrace();
        }
        return proxy;
    }

    @Override
    public void afterPropertiesSet() throws Exception {
        Assert.notNull(type, "type不能为空");
    }

    @Override
    public void setApplicationContext(ApplicationContext applicationContext) throws BeansException {
        this.applicationContext = applicationContext;
    }

    /**
     * 从spring容器获取执行类型bean或者获取默认实现
     *
     * @param clz bean class 类型
     * @param <T>
     * @return bean
     */
    private <T> T getBean(Class<T> clz) {
        T t = null;
        try {
            if (this.applicationContext != null) {
                t = this.applicationContext.getBean(clz);
            }
            if (t == null) {
                log.info("{}使用默认配置", clz);
                t = DefaultRestyPassFactory.getDefaultBean(clz);
            } else {
                log.info("{}使用Spring注入", clz);
            }
            if (t == null) {
                throw new RuntimeException("无法获取Bean:" + clz);
            }
        } catch (BeansException ex) {
            log.info("{}使用默认配置", clz);
            t = DefaultRestyPassFactory.getDefaultBean(clz);
        }
        return t;
    }

    /**
     * 从spring容器中获取bean列表
     *
     * @param clz bean class 类型
     * @param <T>
     * @return bean list/empty_list
     */
    private <T> List<T> getBeans(Class<T> clz) {
        Map<String, T> beansMap = this.applicationContext.getBeansOfType(clz);
        if (CommonTools.isEmpty(beansMap)) {
            return Collections.EMPTY_LIST;
        }

        return new ArrayList(beansMap.values());
    }
}
