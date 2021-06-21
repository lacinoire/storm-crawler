/**
 * Licensed to DigitalPebble Ltd under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * DigitalPebble licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.digitalpebble.stormcrawler.elasticsearch.bolt;

import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.util.HashMap;
import java.util.Map;

import org.apache.http.HttpHost;
import org.apache.storm.task.OutputCollector;
import org.apache.storm.tuple.Tuple;
import org.elasticsearch.action.get.GetRequest;
import org.elasticsearch.action.get.GetResponse;
import org.elasticsearch.client.RequestOptions;
import org.elasticsearch.client.RestClient;
import org.elasticsearch.client.RestClientBuilder;
import org.elasticsearch.client.RestHighLevelClient;
import org.elasticsearch.client.indices.CreateIndexRequest;
import org.elasticsearch.common.xcontent.XContentType;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.elasticsearch.ElasticsearchContainer;

import com.digitalpebble.stormcrawler.Metadata;
import com.digitalpebble.stormcrawler.TestOutputCollector;
import com.digitalpebble.stormcrawler.TestUtil;
import com.digitalpebble.stormcrawler.elasticsearch.persistence.StatusUpdaterBolt;
import com.digitalpebble.stormcrawler.persistence.Status;

public class StatusBoltTest {

    private ElasticsearchContainer container;
    private StatusUpdaterBolt bolt;
    protected TestOutputCollector output;

    protected RestHighLevelClient client;

    private static final Logger LOG = LoggerFactory
            .getLogger(StatusBoltTest.class);

    @Before
    public void setupStatusBolt() throws IOException {

        String version = System.getProperty("elasticsearch-version");
        if (version == null)
            version = "7.5.0";
        LOG.info("Starting docker instance of Elasticsearch {}...", version);

        container = new ElasticsearchContainer(
                "docker.elastic.co/elasticsearch/elasticsearch:" + version);
        container.start();

        bolt = new StatusUpdaterBolt();

        // configure the status index

        RestClientBuilder builder = RestClient.builder(new HttpHost(
                container.getHost(), container.getMappedPort(9200)));

        client = new RestHighLevelClient(builder);

        // TODO
        // https://www.elastic.co/guide/en/elasticsearch/client/java-rest/7.13/java-rest-high-put-mapping.html

        CreateIndexRequest request = new CreateIndexRequest("status");

        File file = new File(getClass().getClassLoader()
                .getResource("status.mapping").getFile());

        String mappingSource = new String(
                java.nio.file.Files.readAllBytes(file.toPath()),
                Charset.defaultCharset());

        request.source(mappingSource, XContentType.JSON);

        client.indices().create(request, RequestOptions.DEFAULT);

        // configure the status updater bolt

        Map conf = new HashMap();
        conf.put("es.status.routing.fieldname", "metadata.key");

        conf.put("es.status.addresses", container.getHttpHostAddress());

        conf.put("scheduler.class",
                "com.digitalpebble.stormcrawler.persistence.DefaultScheduler");

        conf.put("status.updater.cache.spec",
                "maximumSize=10000,expireAfterAccess=1h");

        output = new TestOutputCollector();

        bolt.prepare(conf, TestUtil.getMockedTopologyContext(),
                new OutputCollector(output));
    }

    @After
    public void close() {
        LOG.info("Closing updater bolt and ES container");
        bolt.cleanup();
        container.close();
        output = null;
        try {
            client.close();
        } catch (IOException e) {
        }
    }

    private void store(String url, Status status, Metadata metadata) {
        Tuple tuple = mock(Tuple.class);
        when(tuple.getValueByField("status")).thenReturn(status);
        when(tuple.getStringByField("url")).thenReturn(url);
        when(tuple.getValueByField("metadata")).thenReturn(metadata);
        bolt.execute(tuple);
    }

    @Test
    public void checkKeyFromES() throws IOException {

        String url = "https://www.url.net/something";

        store(url, Status.DISCOVERED, new Metadata());

        // check that something has been emitted out
        while (output.getAckedTuples().size() == 0) {
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }

        assertEquals(1, output.getAckedTuples().size());

        // check output in ES?

        String id = org.apache.commons.codec.digest.DigestUtils.sha256Hex(url);

        GetResponse result = client.get(new GetRequest("status", id),
                RequestOptions.DEFAULT);

        Map sourceAsMap = result.getSourceAsMap();

        String pfield = "metadata.key";

        if (pfield.startsWith("metadata.")) {
            sourceAsMap = (Map<String, Object>) sourceAsMap.get("metadata");
            pfield = pfield.substring(9);
        }
        String key = (String) sourceAsMap.get(pfield);

        assertEquals("www.url.net", key);
    }

}