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

import com.alibaba.nacos.common.logging.NacosLoggingProperties;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.config.Configuration;
import org.apache.logging.log4j.core.config.ConfigurationSource;
import org.apache.logging.log4j.core.config.LoggerConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.beans.PropertyChangeListener;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.Map;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class Log4J2NacosLoggingAdapterTest {
    
    private static final String NACOS_LOGGER_PREFIX = "com.alibaba.nacos";
    
    @Mock
    PropertyChangeListener propertyChangeListener;
    
    NacosLoggingProperties nacosLoggingProperties;
    
    Log4J2NacosLoggingAdapter log4J2NacosLoggingAdapter;
    
    @BeforeEach
    void setUp() throws Exception {
        log4J2NacosLoggingAdapter = new Log4J2NacosLoggingAdapter();
        nacosLoggingProperties = new NacosLoggingProperties("classpath:nacos-log4j2.xml", System.getProperties());
        LoggerContext loggerContext = (LoggerContext) LogManager.getContext(false);
        loggerContext.addPropertyChangeListener(propertyChangeListener);
    }
    
    @AfterEach
    void tearDown() throws Exception {
        LoggerContext loggerContext = (LoggerContext) LogManager.getContext(false);
        loggerContext.removePropertyChangeListener(propertyChangeListener);
        loggerContext.setConfigLocation(loggerContext.getConfigLocation());
        System.clearProperty("nacos.logging.default.config.enabled");
        System.clearProperty("nacos.logging.config");
    }
    
    @Test
    void testIsAdaptedLogger() {
        assertTrue(log4J2NacosLoggingAdapter.isAdaptedLogger(org.apache.logging.slf4j.Log4jLogger.class));
        assertFalse(log4J2NacosLoggingAdapter.isAdaptedLogger(Logger.class));
    }
    
    @Test
    void testIsNeedReloadConfiguration() {
        assertTrue(log4J2NacosLoggingAdapter.isNeedReloadConfiguration());
        log4J2NacosLoggingAdapter.loadConfiguration(nacosLoggingProperties);
        assertFalse(log4J2NacosLoggingAdapter.isNeedReloadConfiguration());
    }
    
    @Test
    void testGetDefaultConfigLocation() {
        assertEquals("classpath:nacos-log4j2.xml", log4J2NacosLoggingAdapter.getDefaultConfigLocation());
    }
    
    @Test
    void testLoadConfiguration() {
        LoggerContext loggerContext = (LoggerContext) LogManager.getContext(false);
        Configuration contextConfiguration = loggerContext.getConfiguration();
        assertEquals(0, contextConfiguration.getLoggers().size());
        log4J2NacosLoggingAdapter.loadConfiguration(nacosLoggingProperties);
        //then
        verify(propertyChangeListener).propertyChange(any());
        loggerContext = (LoggerContext) LogManager.getContext(false);
        contextConfiguration = loggerContext.getConfiguration();
        Map<String, LoggerConfig> nacosClientLoggers = contextConfiguration.getLoggers();
        assertEquals(7, nacosClientLoggers.size());
        for (Map.Entry<String, LoggerConfig> loggerEntry : nacosClientLoggers.entrySet()) {
            String loggerName = loggerEntry.getKey();
            assertTrue(loggerName.startsWith(NACOS_LOGGER_PREFIX));
        }
    }
    
    @Test
    void testLoadConfigurationWithoutLocation() {
        System.setProperty("nacos.logging.default.config.enabled", "false");
        nacosLoggingProperties = new NacosLoggingProperties("classpath:nacos-log4j2.xml", System.getProperties());
        log4J2NacosLoggingAdapter = new Log4J2NacosLoggingAdapter();
        log4J2NacosLoggingAdapter.loadConfiguration(nacosLoggingProperties);
        verify(propertyChangeListener, never()).propertyChange(any());
    }
    
    @Test
    void testLoadConfigurationWithWrongLocation() {
        assertThrows(IllegalStateException.class, () -> {
            System.setProperty("nacos.logging.config", "http://localhost");
            nacosLoggingProperties = new NacosLoggingProperties("classpath:nacos-log4j2.xml", System.getProperties());
            log4J2NacosLoggingAdapter = new Log4J2NacosLoggingAdapter();
            log4J2NacosLoggingAdapter.loadConfiguration(nacosLoggingProperties);
            verify(propertyChangeListener, never()).propertyChange(any());
        });
    }
    
    @Test
    void testGetConfigurationSourceForNonFileProtocol()
            throws NoSuchMethodException, IOException, InvocationTargetException, IllegalAccessException, URISyntaxException {
        Method getConfigurationSourceMethod = Log4J2NacosLoggingAdapter.class.getDeclaredMethod("getConfigurationSource", URL.class);
        getConfigurationSourceMethod.setAccessible(true);
        URL url = mock(URL.class);
        URI uri = mock(URI.class);
        InputStream inputStream = mock(InputStream.class);
        when(uri.toURL()).thenReturn(url);
        when(url.toURI()).thenReturn(uri);
        when(url.openStream()).thenReturn(inputStream);
        when(url.getProtocol()).thenReturn("http");
        ConfigurationSource actual = (ConfigurationSource) getConfigurationSourceMethod.invoke(log4J2NacosLoggingAdapter, url);
        assertEquals(inputStream, actual.getInputStream());
        assertEquals(url, actual.getURL());
    }
    
    // ========== Additional tests for Bug #13940 fix ==========
    
    /**
     * Test MD5 detection logic - no reload when config hasn't changed.
     * 
     * Scenario:
     * 1. First check should return true (need initial load)
     * 2. After loading, hasLoadedOnce = true
     * 3. Second check with unchanged config should return false (no reload)
     * 4. Third check with unchanged config should return false (no reload)
     */
    @Test
    void testIsNeedReloadConfigurationWithMd5CheckNoChange() {
        // First check - Layer 3 should return true
        assertTrue(log4J2NacosLoggingAdapter.isNeedReloadConfiguration(), 
                "First check should return true for initial load");
        
        // Load configuration
        log4J2NacosLoggingAdapter.loadConfiguration(nacosLoggingProperties);
        verify(propertyChangeListener).propertyChange(any());
        
        // Second check - Layer 2 detects no change, should return false
        assertFalse(log4J2NacosLoggingAdapter.isNeedReloadConfiguration(), 
                "Second check should return false when config hasn't changed");
        
        // Third check - Layer 2 still detects no change, should return false
        assertFalse(log4J2NacosLoggingAdapter.isNeedReloadConfiguration(), 
                "Third check should return false when config still hasn't changed");
    }
    
    /**
     * Test appender removal detection (according to author's guidance).
     * 
     * This test simulates the real production scenario:
     * 1. Nacos Client starts and loads its own logging config (nacos-log4j2.xml) with ASYNC_NAMING appender
     * 2. Spring Cloud (or other framework) starts and reloads Log4j2 configuration
     * 3. Spring Cloud's config doesn't include ASYNC_NAMING appender, so it gets removed
     * 4. Nacos detects appender missing and reloads to restore it
     * 
     * Real scenario flow:
     * - Nacos Client init -> load nacos-log4j2.xml -> ASYNC_NAMING appender added to context
     * - Spring Cloud startup -> reload log4j2 config (e.g., log4j2-spring.xml) -> replaces entire config
     * - New config doesn't have ASYNC_NAMING -> appender removed from context
     * - Nacos reload task runs -> detects ASYNC_NAMING missing -> reloads nacos-log4j2.xml -> restores appender
     * 
     * This test aligns with author's guidance: focus on appender presence, not config file MD5.
     */
    @Test
    void testIsNeedReloadConfigurationConfigChanged() throws Exception {
        // Step 1: Nacos Client loads its own config (simulating Nacos Client initialization)
        // This is equivalent to: Nacos Client starts -> loads nacos-log4j2.xml -> ASYNC_NAMING appender added
        log4J2NacosLoggingAdapter.loadConfiguration(nacosLoggingProperties);
        verify(propertyChangeListener).propertyChange(any());
        
        // Verify appender exists after Nacos loads its config
        assertFalse(log4J2NacosLoggingAdapter.isNeedReloadConfiguration(), 
                "Should not reload when appender exists after Nacos config loaded");
        
        // Step 2: Simulate Spring Cloud (or other framework) reloading Log4j2 configuration
        // In real scenario: Spring Cloud startup -> loads its own log4j2 config -> replaces entire Configuration
        // Spring Cloud's config doesn't include ASYNC_NAMING, so it gets removed from context
        // We simulate this by creating a new minimal configuration (like Spring Cloud would) without ASYNC_NAMING
        org.apache.logging.log4j.core.LoggerContext loggerContext = 
                (org.apache.logging.log4j.core.LoggerContext) org.apache.logging.log4j.LogManager.getContext(false);
        org.apache.logging.log4j.core.config.Configuration originalConfig = loggerContext.getConfiguration();
        
        // Create a new minimal configuration without ASYNC_NAMING (simulating external framework reload)
        org.apache.logging.log4j.core.config.builder.api.ConfigurationBuilder<?> builder = 
                org.apache.logging.log4j.core.config.builder.api.ConfigurationBuilderFactory.newConfigurationBuilder();
        builder.setStatusLevel(org.apache.logging.log4j.Level.ERROR);
        builder.setConfigurationName("TestConfig");
        
        // Add a simple console appender (but not ASYNC_NAMING)
        org.apache.logging.log4j.core.config.builder.api.AppenderComponentBuilder consoleAppender = builder
                .newAppender("Console", "Console");
        builder.add(consoleAppender);
        
        // Step 3: Replace the configuration (simulating Spring Cloud replacing Log4j2 config)
        // This is what happens in real scenario: loggerContext.setConfiguration(springCloudConfig)
        // The new config doesn't have ASYNC_NAMING, so it's effectively removed
        org.apache.logging.log4j.core.config.Configuration newConfig = builder.build();
        newConfig.start();
        loggerContext.setConfiguration(newConfig);  // This replaces Nacos config, removing ASYNC_NAMING
        loggerContext.updateLoggers();
        
        // Stop the old configuration (cleanup)
        originalConfig.stop();
        
        // Step 4: Nacos reload task detects appender missing and triggers reload
        // In real scenario: Nacos scheduled task runs -> isNeedReloadConfiguration() -> detects missing
        // -> loadConfiguration() -> reloads nacos-log4j2.xml -> restores ASYNC_NAMING appender
        assertTrue(log4J2NacosLoggingAdapter.isNeedReloadConfiguration(), 
                "Should reload when appender is missing after Spring Cloud replaced the config");
    }
    
    /**
     * Test disabled config scenario.
     */
    @Test
    void testIsNeedReloadConfigurationConfigDisabled() {
        System.setProperty("nacos.logging.default.config.enabled", "false");
        nacosLoggingProperties = new NacosLoggingProperties("classpath:nacos-log4j2.xml", System.getProperties());
        
        // Load config (actually location is null, won't load)
        log4J2NacosLoggingAdapter.loadConfiguration(nacosLoggingProperties);
        
        // Check - config disabled, Layer 2 should detect null == null, return false
        assertFalse(log4J2NacosLoggingAdapter.isNeedReloadConfiguration(), 
                "Should not reload when config is disabled");
    }
    
    /**
     * Test calculateConfigMd5 method - success case.
     */
    @Test
    void testCalculateConfigMd5Success() throws Exception {
        Method calculateConfigMd5Method = Log4J2NacosLoggingAdapter.class
                .getDeclaredMethod("calculateConfigMd5", String.class);
        calculateConfigMd5Method.setAccessible(true);
        
        // Test normal case - use Nacos default config file
        String md5 = (String) calculateConfigMd5Method.invoke(
                log4J2NacosLoggingAdapter, "classpath:nacos-log4j2.xml");
        
        assertNotNull(md5);
        assertEquals(32, md5.length());
        assertTrue(md5.matches("[0-9a-f]{32}"));
    }
    
    /**
     * Test calculateConfigMd5 method - null input.
     */
    @Test
    void testCalculateConfigMd5NullLocation() throws Exception {
        Method calculateConfigMd5Method = Log4J2NacosLoggingAdapter.class
                .getDeclaredMethod("calculateConfigMd5", String.class);
        calculateConfigMd5Method.setAccessible(true);
        
        // Test null input
        String md5 = (String) calculateConfigMd5Method.invoke(
                log4J2NacosLoggingAdapter, (String) null);
        
        assertNull(md5);
    }
    
    /**
     * Test calculateConfigMd5 method - empty string input.
     */
    @Test
    void testCalculateConfigMd5EmptyLocation() throws Exception {
        Method calculateConfigMd5Method = Log4J2NacosLoggingAdapter.class
                .getDeclaredMethod("calculateConfigMd5", String.class);
        calculateConfigMd5Method.setAccessible(true);
        
        // Test empty string
        String md5 = (String) calculateConfigMd5Method.invoke(
                log4J2NacosLoggingAdapter, "");
        
        assertNull(md5);
    }
    
    /**
     * Test calculateConfigMd5 method - invalid file path.
     */
    @Test
    void testCalculateConfigMd5InvalidLocation() throws Exception {
        Method calculateConfigMd5Method = Log4J2NacosLoggingAdapter.class
                .getDeclaredMethod("calculateConfigMd5", String.class);
        calculateConfigMd5Method.setAccessible(true);
        
        // Test non-existent file - should return null, not throw exception
        String md5 = (String) calculateConfigMd5Method.invoke(
                log4J2NacosLoggingAdapter, "file:///not-exist-file.xml");
        
        assertNull(md5);
    }
    
    /**
     * Test Layer 1 fast path with ASYNC_NAMING appender.
     */
    @Test
    void testIsNeedReloadConfigurationWithAsyncNamingAppender() {
        // Load config (contains ASYNC_NAMING)
        log4J2NacosLoggingAdapter.loadConfiguration(nacosLoggingProperties);
        
        // Check - Layer 1 should detect ASYNC_NAMING and return false quickly
        assertFalse(log4J2NacosLoggingAdapter.isNeedReloadConfiguration(), 
                "Should not reload when ASYNC_NAMING appender exists");
        
        // Verify ASYNC_NAMING appender exists in Log4j2 context
        LoggerContext loggerContext = (LoggerContext) LogManager.getContext(false);
        Configuration contextConfiguration = loggerContext.getConfiguration();
        assertTrue(contextConfiguration.getAppenders().containsKey("ASYNC_NAMING"), 
                "ASYNC_NAMING appender should exist in Log4j2 context");
    }
    
    /**
     * Test initial state of fields.
     */
    @Test
    void testInitialState() throws Exception {
        Log4J2NacosLoggingAdapter adapter = new Log4J2NacosLoggingAdapter();
        
        // Check initial state via reflection
        java.lang.reflect.Field hasLoadedOnceField = Log4J2NacosLoggingAdapter.class.getDeclaredField("hasLoadedOnce");
        hasLoadedOnceField.setAccessible(true);
        assertFalse((Boolean) hasLoadedOnceField.get(adapter));
        
        java.lang.reflect.Field lastConfigLocationField = Log4J2NacosLoggingAdapter.class.getDeclaredField("lastConfigLocation");
        lastConfigLocationField.setAccessible(true);
        assertNull(lastConfigLocationField.get(adapter));
        
        java.lang.reflect.Field lastConfigMd5Field = Log4J2NacosLoggingAdapter.class.getDeclaredField("lastConfigMd5");
        lastConfigMd5Field.setAccessible(true);
        assertNull(lastConfigMd5Field.get(adapter));
    }
    
    /**
     * Test multiple loads of same config.
     */
    @Test
    void testLoadConfigurationMultiple() throws Exception {
        // First load
        log4J2NacosLoggingAdapter.loadConfiguration(nacosLoggingProperties);
        
        // Get first MD5
        java.lang.reflect.Field lastConfigMd5Field = Log4J2NacosLoggingAdapter.class.getDeclaredField("lastConfigMd5");
        lastConfigMd5Field.setAccessible(true);
        String firstMd5 = (String) lastConfigMd5Field.get(log4J2NacosLoggingAdapter);
        
        // Second load of same config
        log4J2NacosLoggingAdapter.loadConfiguration(nacosLoggingProperties);
        
        // Get second MD5
        String secondMd5 = (String) lastConfigMd5Field.get(log4J2NacosLoggingAdapter);
        
        // MD5 should be the same (same config content)
        assertEquals(firstMd5, secondMd5);
        
        // Check should return false (config unchanged)
        assertFalse(log4J2NacosLoggingAdapter.isNeedReloadConfiguration());
    }
}