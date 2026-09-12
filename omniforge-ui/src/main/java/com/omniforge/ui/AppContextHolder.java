package com.omniforge.ui;

import org.springframework.context.ApplicationContext;

/**
 * Spring 上下文持有器：由 omniforge-app 的启动器在装配完成后注入，
 * JavaFX Application（OmniForgeApplication）经此获取各模块 Bean。
 *
 * <p>设计说明：app 模块依赖 ui 模块，故持有器随 ui 模块发布（app 反向注入）。</p>
 */
public final class AppContextHolder {

    private static volatile ApplicationContext context;

    private AppContextHolder() {
    }

    public static void set(ApplicationContext applicationContext) {
        context = applicationContext;
    }

    public static ApplicationContext get() {
        return context;
    }
}
