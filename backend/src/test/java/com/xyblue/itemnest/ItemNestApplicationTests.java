package com.xyblue.itemnest;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = {
    "itemnest.data-dir=target/test-data",
    "itemnest.rabbitmq.enabled=false"
})
class ItemNestApplicationTests {
    @Test
    void contextLoads() {}
}
