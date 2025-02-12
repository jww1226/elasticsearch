/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0 and the Server Side Public License, v 1; you may not use this file except
 * in compliance with, at your election, the Elastic License 2.0 or the Server
 * Side Public License, v 1.
 */

package org.elasticsearch.telemetry.apm;

import org.apache.lucene.util.SetOnce;
import org.elasticsearch.client.internal.Client;
import org.elasticsearch.cluster.metadata.IndexNameExpressionResolver;
import org.elasticsearch.cluster.routing.allocation.AllocationService;
import org.elasticsearch.cluster.service.ClusterService;
import org.elasticsearch.common.io.stream.NamedWriteableRegistry;
import org.elasticsearch.common.settings.Setting;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.env.Environment;
import org.elasticsearch.env.NodeEnvironment;
import org.elasticsearch.indices.IndicesService;
import org.elasticsearch.plugins.NetworkPlugin;
import org.elasticsearch.plugins.Plugin;
import org.elasticsearch.plugins.TelemetryPlugin;
import org.elasticsearch.repositories.RepositoriesService;
import org.elasticsearch.script.ScriptService;
import org.elasticsearch.telemetry.TelemetryProvider;
import org.elasticsearch.telemetry.apm.internal.APMAgentSettings;
import org.elasticsearch.telemetry.apm.internal.APMMeterService;
import org.elasticsearch.telemetry.apm.internal.APMTelemetryProvider;
import org.elasticsearch.telemetry.apm.internal.tracing.APMTracer;
import org.elasticsearch.threadpool.ThreadPool;
import org.elasticsearch.watcher.ResourceWatcherService;
import org.elasticsearch.xcontent.NamedXContentRegistry;

import java.util.Collection;
import java.util.List;
import java.util.function.Supplier;

/**
 * This module integrates Elastic's APM product with Elasticsearch. Elasticsearch has
 * a {@link org.elasticsearch.telemetry.tracing.Tracer} interface, which this module implements via
 * {@link APMTracer}. We use the OpenTelemetry API to capture "spans", and attach the
 * Elastic APM Java to ship those spans to an APM server. Although it is possible to
 * programmatically attach the agent, the Security Manager permissions required for this
 * make this approach difficult to the point of impossibility.
 * <p>
 * All settings are found under the <code>tracing.apm.</code> prefix. Any setting under
 * the <code>tracing.apm.agent.</code> prefix will be forwarded on to the APM Java agent
 * by setting appropriate system properties. Some settings can only be set once, and must be
 * set when the agent starts. We therefore also create and configure a config file in
 * the {@code APMJvmOptions} class, which we then delete when Elasticsearch starts, so that
 * sensitive settings such as <code>secret_token</code> or <code>api_key</code> are not
 * left on disk.
 * <p>
 * When settings are reconfigured using the settings REST API, the new values will again
 * be passed via system properties to the Java agent, which periodically checks for changes
 * and applies the new settings values, provided those settings can be dynamically updated.
 */
public class APM extends Plugin implements NetworkPlugin, TelemetryPlugin {
    private final SetOnce<APMTelemetryProvider> telemetryProvider = new SetOnce<>();
    private final Settings settings;

    public APM(Settings settings) {
        this.settings = settings;
    }

    @Override
    public TelemetryProvider getTelemetryProvider(Settings settings) {
        final APMTelemetryProvider apmTelemetryProvider = new APMTelemetryProvider(settings);
        telemetryProvider.set(apmTelemetryProvider);
        return apmTelemetryProvider;
    }

    @Override
    public Collection<Object> createComponents(
        Client client,
        ClusterService clusterService,
        ThreadPool threadPool,
        ResourceWatcherService resourceWatcherService,
        ScriptService scriptService,
        NamedXContentRegistry xContentRegistry,
        Environment environment,
        NodeEnvironment nodeEnvironment,
        NamedWriteableRegistry namedWriteableRegistry,
        IndexNameExpressionResolver indexNameExpressionResolver,
        Supplier<RepositoriesService> repositoriesServiceSupplier,
        TelemetryProvider unused,
        AllocationService allocationService,
        IndicesService indicesService
    ) {
        final APMTracer apmTracer = telemetryProvider.get().getTracer();

        apmTracer.setClusterName(clusterService.getClusterName().value());
        apmTracer.setNodeName(clusterService.getNodeName());

        final APMAgentSettings apmAgentSettings = new APMAgentSettings();
        apmAgentSettings.syncAgentSystemProperties(settings);
        final APMMeterService apmMeter = new APMMeterService(settings);
        apmAgentSettings.addClusterSettingsListeners(clusterService, telemetryProvider.get(), apmMeter);

        return List.of(apmTracer, apmMeter);
    }

    @Override
    public List<Setting<?>> getSettings() {
        return List.of(
            APMAgentSettings.APM_ENABLED_SETTING,
            APMAgentSettings.TELEMETRY_METRICS_ENABLED_SETTING,
            APMAgentSettings.APM_TRACING_NAMES_INCLUDE_SETTING,
            APMAgentSettings.APM_TRACING_NAMES_EXCLUDE_SETTING,
            APMAgentSettings.APM_TRACING_SANITIZE_FIELD_NAMES,
            APMAgentSettings.APM_AGENT_SETTINGS,
            APMAgentSettings.APM_SECRET_TOKEN_SETTING,
            APMAgentSettings.APM_API_KEY_SETTING
        );
    }
}
