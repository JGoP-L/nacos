/*
 * Copyright 1999-2023 Alibaba Group Holding Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.alibaba.nacos.logger.adapter.log4j2;

import com.alibaba.nacos.common.logging.NacosLoggingAdapter;
import com.alibaba.nacos.common.logging.NacosLoggingProperties;
import com.alibaba.nacos.common.utils.IoUtils;
import com.alibaba.nacos.common.utils.MD5Utils;
import com.alibaba.nacos.common.utils.ResourceUtils;
import com.alibaba.nacos.common.utils.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.Appender;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.config.Configuration;
import org.apache.logging.log4j.core.config.ConfigurationFactory;
import org.apache.logging.log4j.core.config.ConfigurationSource;
import org.apache.logging.log4j.core.config.LoggerConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.Map;

/**
 * Support for Log4j version 2.7 or higher
 *
 * @author <a href="mailto:huangxiaoyu1018@gmail.com">hxy1991</a>
 * @author xiweng.yy
 * @since 0.9.0
 */
public class Log4J2NacosLoggingAdapter implements NacosLoggingAdapter {
    
    private static final Logger LOGGER = LoggerFactory.getLogger(Log4J2NacosLoggingAdapter.class);
    
    private static final String NACOS_LOG4J2_LOCATION = "classpath:nacos-log4j2.xml";
    
    private static final String FILE_PROTOCOL = "file";
    
    private static final String NACOS_LOGGER_PREFIX = "com.alibaba.nacos";
    
    private static final String APPENDER_MARK = "ASYNC_NAMING";
    
    private static final String LOG4J2_CLASSES = "org.apache.logging.slf4j.Log4jLogger";
    
    /**
     * Whether configuration has been loaded at least once.
     */
    private volatile boolean hasLoadedOnce = false;
    
    /**
     * Last loaded configuration location.
     */
    private volatile String lastConfigLocation = null;
    
    /**
     * MD5 hash of last loaded configuration content.
     */
    private volatile String lastConfigMd5 = null;
    
    /**
     * Track when ASYNC_NAMING appender was last seen in context.
     * Used for diagnostic purposes to understand when appender disappears.
     */
    private volatile long lastSeenAppenderTime = 0;
    
    /**
     * Track how many times we've detected appender missing after it was loaded.
     * Used to identify if there's a pattern of appender removal.
     */
    private volatile int appenderMissingCount = 0;
    
    @Override
    public boolean isAdaptedLogger(Class<?> loggerClass) {
        Class<?> expectedLoggerClass = getExpectedLoggerClass();
        return null != expectedLoggerClass && expectedLoggerClass.isAssignableFrom(loggerClass);
    }
    
    private Class<?> getExpectedLoggerClass() {
        try {
            return Class.forName(LOG4J2_CLASSES);
        } catch (ClassNotFoundException e) {
            return null;
        }
    }
    
    @Override
    public boolean isNeedReloadConfiguration() {
        // Layer 1: Fast path - check if Nacos-specific appender exists
        // This indicates Nacos configuration has been successfully loaded
        final LoggerContext loggerContext = (LoggerContext) LogManager.getContext(false);
        final Configuration contextConfiguration = loggerContext.getConfiguration();
        
        // Diagnostic: Collect all appender names for troubleshooting
        java.util.Set<String> allAppenderNames = new java.util.HashSet<>();
        
        for (Map.Entry<String, Appender> entry : contextConfiguration.getAppenders().entrySet()) {
            String appenderName = entry.getValue().getName();
            allAppenderNames.add(appenderName);
            if (APPENDER_MARK.equals(appenderName)) {
                // Nacos configuration is active, no reload needed
                lastSeenAppenderTime = System.currentTimeMillis();
                appenderMissingCount = 0; // Reset counter when appender is found
                if (LOGGER.isTraceEnabled()) {
                    LOGGER.trace("Found {} appender in context, no reload needed. All appenders: {}", 
                            APPENDER_MARK, allAppenderNames);
                }
                return false;
            }
        }
        
        // Layer 2: Appender not found - check if we need to reload
        // This happens when:
        // 1. Spring Cloud or other frameworks reload logging config and remove our appender
        // 2. First time loading (hasLoadedOnce = false)
        // 3. User disabled Nacos default config (handled by checking location)
        // 4. User provided custom config without ASYNC_NAMING appender (handled by checking location)
        
        // Diagnostic logging: Log detailed information for troubleshooting
        String currentLocation = getCurrentConfigLocation();
        long currentTime = System.currentTimeMillis();
        long timeSinceLastSeen = lastSeenAppenderTime > 0 ? currentTime - lastSeenAppenderTime : -1;
        
        // Increment missing count for diagnostic purposes
        if (hasLoadedOnce) {
            appenderMissingCount++;
        }
        
        LOGGER.info("Checking if reload needed: appender={} not found, hasLoadedOnce={}, "
                        + "currentLocation={}, lastConfigLocation={}, allAppenders={}, "
                        + "appenderMissingCount={}, timeSinceLastSeen={}ms, contextConfigSource={}",
                APPENDER_MARK, hasLoadedOnce, currentLocation, lastConfigLocation, allAppenderNames,
                appenderMissingCount, timeSinceLastSeen, getContextConfigurationSource(contextConfiguration));
        
        if (hasLoadedOnce) {
            // Configuration was loaded before but appender not found
            // This likely means the appender was removed by external framework (e.g., Spring Cloud)
            // We should reload to restore the appender, regardless of config file MD5
            // because the purpose is to restore the appender, not to respond to config file changes
            
            // If location is null or blank, it means config is disabled, don't reload
            if (StringUtils.isBlank(currentLocation)) {
                LOGGER.info("Config location is blank, config disabled. Will not reload.");
                return false;
            }
            
            // Appender is missing but we've loaded before - need to reload to restore it
            // This is the core fix: reload when appender is removed, not just when config file changes
            // According to author's guidance: we need to investigate WHY appender is missing
            LOGGER.warn("Nacos appender {} not found in context but was loaded before. "
                            + "This may indicate external framework (e.g., Spring Cloud) removed it. "
                            + "Will reload to restore. All appenders in context: {}, "
                            + "Thread: {}, StackTrace: {}, "
                            + "ContextState: configSource={}, configName={}, started={}",
                    APPENDER_MARK, allAppenderNames, Thread.currentThread().getName(),
                    getStackTrace(), getContextConfigurationSource(contextConfiguration),
                    contextConfiguration.getName(), contextConfiguration.isStarted());
            
            // Perform deep diagnostic check
            performDeepDiagnostic(contextConfiguration, allAppenderNames);
            
            // Log diagnostic tool usage hint
            LOGGER.info("To get detailed diagnostic information, call: "
                    + "com.alibaba.nacos.logger.adapter.log4j2.Log4j2DiagnosticTool.diagnose(System.out)");
            
            return true;
        }
        
        // Layer 3: First time loading
        LOGGER.info("First time loading, will load configuration. All appenders in context: {}", 
                allAppenderNames);
        return true;
    }
    
    /**
     * Get current stack trace for diagnostic purposes.
     * 
     * @return stack trace string
     */
    private String getStackTrace() {
        StackTraceElement[] stackTrace = Thread.currentThread().getStackTrace();
        StringBuilder sb = new StringBuilder();
        // Skip first 3 elements (getStackTrace, isNeedReloadConfiguration, caller)
        int limit = Math.min(stackTrace.length, 10);
        for (int i = 3; i < limit; i++) {
            if (i > 3) {
                sb.append(" <- ");
            }
            sb.append(stackTrace[i].getClassName())
                    .append(".")
                    .append(stackTrace[i].getMethodName())
                    .append(":")
                    .append(stackTrace[i].getLineNumber());
        }
        return sb.toString();
    }
    
    /**
     * Get context configuration source for diagnostic purposes.
     * 
     * @param configuration the configuration
     * @return configuration source string
     */
    private String getContextConfigurationSource(Configuration configuration) {
        try {
            org.apache.logging.log4j.core.config.ConfigurationSource source = configuration.getConfigurationSource();
            if (source != null) {
                if (source.getFile() != null) {
                    return "file:" + source.getFile().getAbsolutePath();
                } else if (source.getURL() != null) {
                    return "url:" + source.getURL().toString();
                }
            }
            return "unknown";
        } catch (Exception e) {
            return "error:" + e.getMessage();
        }
    }
    
    /**
     * Perform deep diagnostic check to understand why appender is missing.
     * This method investigates the root cause according to author's guidance.
     * 
     * @param configuration the current configuration
     * @param existingAppenders all existing appender names
     */
    private void performDeepDiagnostic(Configuration configuration, java.util.Set<String> existingAppenders) {
        try {
            // Check if configuration was recently changed/reloaded
            LOGGER.debug("Deep diagnostic: Configuration name={}, started={}, "
                            + "rootLogger={}, loggerCount={}, appenderCount={}",
                    configuration.getName(), configuration.isStarted(),
                    configuration.getRootLogger().getName(),
                    configuration.getLoggers().size(),
                    configuration.getAppenders().size());
            
            // Check if there are any listeners or watchers that might have modified the context
            final LoggerContext loggerContext = (LoggerContext) LogManager.getContext(false);
            LOGGER.debug("Deep diagnostic: LoggerContext name={}",
                    loggerContext.getName());
            
            // Check if ASYNC_NAMING was ever added but then removed
            // This would indicate external framework interference
            if (appenderMissingCount > 1) {
                LOGGER.warn("Deep diagnostic: ASYNC_NAMING appender has been missing {} times. "
                                + "This suggests a pattern of appender removal, possibly by external framework. "
                                + "Last seen {}ms ago.",
                        appenderMissingCount, 
                        lastSeenAppenderTime > 0 ? System.currentTimeMillis() - lastSeenAppenderTime : -1);
            }
            
            // Log all appenders with their types for comparison
            java.util.Map<String, String> appenderTypes = new java.util.HashMap<>();
            for (Map.Entry<String, Appender> entry : configuration.getAppenders().entrySet()) {
                appenderTypes.put(entry.getKey(), entry.getValue().getClass().getSimpleName());
            }
            LOGGER.debug("Deep diagnostic: All appenders with types: {}", appenderTypes);
            
        } catch (Exception e) {
            LOGGER.warn("Error during deep diagnostic check: {}", e.getMessage(), e);
        }
    }
    
    @Override
    public String getDefaultConfigLocation() {
        return NACOS_LOG4J2_LOCATION;
    }
    
    @Override
    public void loadConfiguration(NacosLoggingProperties loggingProperties) {
        Log4j2NacosLoggingPropertiesHolder.setProperties(loggingProperties);
        String location = loggingProperties.getLocation();
        loadConfiguration(location);
        
        // Record loading state to prevent unnecessary reloads
        hasLoadedOnce = true;
        lastConfigLocation = location;
        lastConfigMd5 = calculateConfigMd5(location);
        
        LOGGER.info("Nacos logging configuration loaded from: {}", location);
    }
    
    private void loadConfiguration(String location) {
        if (StringUtils.isBlank(location)) {
            LOGGER.debug("Configuration location is blank, skipping load");
            return;
        }
        final LoggerContext loggerContext = (LoggerContext) LogManager.getContext(false);
        final Configuration contextConfiguration = loggerContext.getConfiguration();
        
        // Diagnostic: Log appenders before loading
        java.util.Set<String> appendersBefore = new java.util.HashSet<>();
        for (Appender appender : contextConfiguration.getAppenders().values()) {
            appendersBefore.add(appender.getName());
        }
        LOGGER.info("Loading Nacos logging configuration from: {}. Appenders before load: {}", 
                location, appendersBefore);
        
        // load and start nacos configuration
        Configuration configuration = loadConfiguration(loggerContext, location);
        configuration.start();
        
        // append loggers and appenders to contextConfiguration
        Map<String, Appender> appenders = configuration.getAppenders();
        java.util.Set<String> addedAppenders = new java.util.HashSet<>();
        for (Appender appender : appenders.values()) {
            String appenderName = appender.getName();
            contextConfiguration.addAppender(appender);
            addedAppenders.add(appenderName);
        }
        Map<String, LoggerConfig> loggers = configuration.getLoggers();
        int addedLoggerCount = 0;
        for (String name : loggers.keySet()) {
            if (name.startsWith(NACOS_LOGGER_PREFIX)) {
                contextConfiguration.addLogger(name, loggers.get(name));
                addedLoggerCount++;
            }
        }
        
        loggerContext.updateLoggers();
        
        // Diagnostic: Log appenders after loading
        java.util.Set<String> appendersAfter = new java.util.HashSet<>();
        for (Appender appender : contextConfiguration.getAppenders().values()) {
            appendersAfter.add(appender.getName());
        }
        boolean asycnNamingExists = appendersAfter.contains(APPENDER_MARK);
        
        // Update tracking when appender is successfully loaded
        if (asycnNamingExists) {
            lastSeenAppenderTime = System.currentTimeMillis();
            appenderMissingCount = 0;
        }
        
        LOGGER.info("Nacos logging configuration loaded. Added appenders: {}, Added loggers: {}, "
                        + "ASYNC_NAMING exists: {}, All appenders after load: {}",
                addedAppenders, addedLoggerCount, asycnNamingExists, appendersAfter);
        
        if (!asycnNamingExists) {
            LOGGER.error("ASYNC_NAMING appender was not found after loading configuration from {}. "
                            + "This indicates a serious configuration issue. "
                            + "Expected appenders: {}, Actually added: {}, "
                            + "Configuration file may be missing ASYNC_NAMING definition or loading failed.",
                    location, appenders.keySet(), addedAppenders);
            
            // Deep diagnostic: Check if the configuration file actually contains ASYNC_NAMING
            try {
                URL url = ResourceUtils.getResourceUrl(location);
                if (url != null) {
                    String configContent = IoUtils.toString(url.openStream(), "UTF-8");
                    boolean containsAsyncNaming = configContent.contains("ASYNC_NAMING") 
                            || configContent.contains("name=\"ASYNC_NAMING\"");
                    LOGGER.warn("Configuration file check: contains ASYNC_NAMING definition: {}", 
                            containsAsyncNaming);
                    if (!containsAsyncNaming) {
                        LOGGER.error("Configuration file {} does not contain ASYNC_NAMING definition. "
                                        + "This is the root cause of the issue.",
                                location);
                    }
                }
            } catch (Exception e) {
                LOGGER.warn("Could not verify configuration file content: {}", e.getMessage());
            }
        }
    }
    
    private Configuration loadConfiguration(LoggerContext loggerContext, String location) {
        try {
            URL url = ResourceUtils.getResourceUrl(location);
            ConfigurationSource source = getConfigurationSource(url);
            // since log4j 2.7 getConfiguration(LoggerContext loggerContext, ConfigurationSource source)
            return ConfigurationFactory.getInstance().getConfiguration(loggerContext, source);
        } catch (Exception e) {
            throw new IllegalStateException("Could not initialize Log4J2 logging from " + location, e);
        }
    }
    
    private ConfigurationSource getConfigurationSource(URL url) throws IOException {
        InputStream stream = url.openStream();
        if (FILE_PROTOCOL.equals(url.getProtocol())) {
            return new ConfigurationSource(stream, ResourceUtils.getResourceAsFile(url));
        }
        return new ConfigurationSource(stream, url);
    }
    
    /**
     * Get current configuration location.
     * Since we already stored it in lastConfigLocation, we can just return it.
     *
     * @return current configuration location
     */
    private String getCurrentConfigLocation() {
        return lastConfigLocation;
    }
    
    /**
     * Calculate MD5 hash of configuration file content.
     * This method follows the same pattern as TlsFileWatcher in Nacos framework.
     *
     * @param location configuration file location
     * @return MD5 hash string, or null if calculation fails
     */
    private String calculateConfigMd5(String location) {
        if (StringUtils.isBlank(location)) {
            return null;
        }
        
        InputStream in = null;
        try {
            URL url = ResourceUtils.getResourceUrl(location);
            in = url.openStream();
            String content = IoUtils.toString(in, "UTF-8");
            return MD5Utils.md5Hex(content, "UTF-8");
        } catch (Exception e) {
            // Don't log error for expected cases (e.g., config disabled)
            LOGGER.debug("Failed to calculate MD5 for config location {}: {}", location, e.getMessage());
            return null;
        } finally {
            IoUtils.closeQuietly(in);
        }
    }
}
