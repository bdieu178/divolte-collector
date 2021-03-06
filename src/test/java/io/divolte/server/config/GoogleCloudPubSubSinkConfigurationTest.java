/*
 * Copyright 2018 GoDataDriven B.V.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.divolte.server.config;

import com.google.api.gax.batching.BatchingSettings;
import com.google.api.gax.retrying.RetrySettings;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

public class GoogleCloudPubSubSinkConfigurationTest {

    @Test
    public void testDefaultRetryConfigurationValid() {
        // Check that we can generate settings from our defaults.
        final RetrySettings retrySettings =
            GoogleCloudPubSubSinkConfiguration.DEFAULT_RETRY_SETTINGS.createRetrySettings();
        assertNotNull(retrySettings);
    }

    @Test
    public void testDefaultBatchingConfigurationValid() {
        // Check that we can generate settings from our defaults.
        final BatchingSettings batchingSettings =
            GoogleCloudPubSubSinkConfiguration.DEFAULT_BATCHING_SETTINGS.createBatchingSettings();
        assertNotNull(batchingSettings);
    }

    @Test
    public void testRetryMaxRpcTimeoutDefaultsToInitialRpcTimeout() {
        final RetrySettings retrySettings =
            new GoogleRetryConfiguration(null, null, null, null, null, java.time.Duration.ofNanos(138263), null, null).createRetrySettings();
        assertEquals(retrySettings.getInitialRpcTimeout(), retrySettings.getMaxRpcTimeout());
    }
}
