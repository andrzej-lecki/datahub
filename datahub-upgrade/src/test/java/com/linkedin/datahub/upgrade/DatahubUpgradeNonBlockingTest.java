package com.linkedin.datahub.upgrade;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;
import static org.testng.AssertJUnit.assertNotNull;

import com.linkedin.datahub.upgrade.impl.DefaultUpgradeManager;
import com.linkedin.datahub.upgrade.system.SystemUpdateNonBlocking;
import com.linkedin.datahub.upgrade.system.bootstrapmcps.BootstrapMCPStep;
import com.linkedin.datahub.upgrade.system.restoreindices.graph.vianodes.ReindexDataJobViaNodesCLL;
import com.linkedin.metadata.boot.kafka.MockSystemUpdateDeserializer;
import com.linkedin.metadata.boot.kafka.MockSystemUpdateSerializer;
import com.linkedin.metadata.config.kafka.KafkaConfiguration;
import com.linkedin.metadata.dao.producer.KafkaEventProducer;
import com.linkedin.metadata.entity.AspectDao;
import com.linkedin.metadata.entity.EntityService;
import com.linkedin.metadata.entity.EntityServiceImpl;
import com.linkedin.metadata.entity.restoreindices.RestoreIndicesArgs;
import com.linkedin.mxe.Topics;
import io.datahubproject.metadata.context.OperationContext;
import io.datahubproject.test.metadata.context.TestOperationContexts;
import java.util.List;
import java.util.stream.Collectors;
import javax.inject.Named;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.testng.AbstractTestNGSpringContextTests;
import org.testng.annotations.Test;

@ActiveProfiles("test")
@SpringBootTest(
    classes = {UpgradeCliApplication.class, UpgradeCliApplicationTestConfiguration.class},
    properties = {
      "BOOTSTRAP_SYSTEM_UPDATE_DATA_JOB_NODE_CLL_ENABLED=true",
      "kafka.schemaRegistry.type=INTERNAL",
      "DATAHUB_UPGRADE_HISTORY_TOPIC_NAME=" + Topics.DATAHUB_UPGRADE_HISTORY_TOPIC_NAME,
      "METADATA_CHANGE_LOG_VERSIONED_TOPIC_NAME=" + Topics.METADATA_CHANGE_LOG_VERSIONED,
    },
    args = {"-u", "SystemUpdateNonBlocking"})
public class DatahubUpgradeNonBlockingTest extends AbstractTestNGSpringContextTests {

  @Autowired
  @Named("systemUpdateNonBlocking")
  private SystemUpdateNonBlocking systemUpdateNonBlocking;

  @Autowired
  @Named("schemaRegistryConfig")
  private KafkaConfiguration.SerDeKeyValueConfig schemaRegistryConfig;

  @Autowired
  @Named("duheKafkaEventProducer")
  private KafkaEventProducer duheKafkaEventProducer;

  @Autowired
  @Named("kafkaEventProducer")
  private KafkaEventProducer kafkaEventProducer;

  @Autowired private EntityServiceImpl entityService;

  @Autowired private OperationContext opContext;

  @Test
  public void testSystemUpdateNonBlockingInit() {
    assertNotNull(systemUpdateNonBlocking);

    // Expected system update configuration and producer
    assertEquals(
        schemaRegistryConfig.getValue().getDeserializer(),
        MockSystemUpdateDeserializer.class.getName());
    assertEquals(
        schemaRegistryConfig.getValue().getSerializer(),
        MockSystemUpdateSerializer.class.getName());
    assertEquals(duheKafkaEventProducer, kafkaEventProducer);
    assertEquals(entityService.getProducer(), duheKafkaEventProducer);
  }

  @Test
  public void testReindexDataJobViaNodesCLLPaging() {
    EntityService<?> mockService = mock(EntityService.class);

    AspectDao mockAspectDao = mock(AspectDao.class);

    ReindexDataJobViaNodesCLL cllUpgrade =
        new ReindexDataJobViaNodesCLL(opContext, mockService, mockAspectDao, true, 10, 0, 0);
    SystemUpdateNonBlocking upgrade = new SystemUpdateNonBlocking(List.of(cllUpgrade), null);
    DefaultUpgradeManager manager = new DefaultUpgradeManager();
    manager.register(upgrade);
    manager.execute(
        TestOperationContexts.systemContextNoSearchAuthorization(),
        "SystemUpdateNonBlocking",
        List.of());
    verify(mockAspectDao, times(1))
        .streamAspectBatches(
            eq(
                new RestoreIndicesArgs()
                    .batchSize(10)
                    .limit(0)
                    .aspectName("dataJobInputOutput")
                    .urnLike("urn:li:dataJob:%")));
  }

  @Test
  public void testNonBlockingBootstrapMCP() {
    List<BootstrapMCPStep> mcpTemplate =
        systemUpdateNonBlocking.steps().stream()
            .filter(update -> update instanceof BootstrapMCPStep)
            .map(update -> (BootstrapMCPStep) update)
            .toList();

    assertFalse(mcpTemplate.isEmpty());
    assertTrue(
        mcpTemplate.stream().noneMatch(update -> update.getMcpTemplate().isBlocking()),
        String.format(
            "Found blocking step: %s (expected non-blocking only)",
            mcpTemplate.stream()
                .filter(update -> update.getMcpTemplate().isBlocking())
                .map(update -> update.getMcpTemplate().getName())
                .collect(Collectors.toSet())));
  }
}
