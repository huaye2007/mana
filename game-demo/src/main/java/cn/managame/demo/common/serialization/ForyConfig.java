package cn.managame.demo.common.serialization;

import cn.managame.demo.common.protocol.GameProtocols;
import org.apache.fory.Fory;
import org.apache.fory.ThreadSafeFory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

@Configuration
@Import(GameProtocols.class)
public class ForyConfig {
    @Bean
    public ThreadSafeFory fory(GameProtocols protocols) {
        // Reuse a bounded shared serializer pool for EventLoops and virtual threads.
        ThreadSafeFory fory = Fory.builder()
                .withXlang(false)
                .requireClassRegistration(true)
                .withAsyncCompilation(false)
                .buildThreadSafeFory();
        // Both peers must use the same type IDs; register before concurrent use.
        protocols.registerFory(fory);
        return fory;
    }
}
