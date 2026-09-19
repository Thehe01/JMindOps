package com.kama.jmindops.service;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;

import static org.junit.jupiter.api.Assertions.assertNotNull;

@SpringBootTest(classes = { DocumentIndexTaskWorker.class, IndexRetryPolicy.class })
public class DocumentIndexTaskWorkerContextTest {

    @MockBean
    private DocumentIndexTaskStore store;

    @MockBean
    private DocumentIndexTaskExecutor executor;

    @Autowired
    private DocumentIndexTaskWorker worker;

    @Test
    public void testWorkerIsInstantiatedBySpring() {
        assertNotNull(worker, "ApplicationContext should instantiate DocumentIndexTaskWorker");
    }
}
