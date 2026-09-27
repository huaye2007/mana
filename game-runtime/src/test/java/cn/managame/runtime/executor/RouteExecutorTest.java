package cn.managame.runtime.executor;

import org.junit.jupiter.params.*;
import org.junit.jupiter.params.provider.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;
class RouteExecutorTest {
    @ParameterizedTest @ValueSource(booleans={false,true})
    void orderingIsolationDrainAndFailureSurvival(boolean virtual) throws Exception {
        RouteExecutor executor=virtual ? new VirtualThreadRouteExecutor(1000) : new StripedRouteExecutor(2,1000);
        List<Integer> order=new CopyOnWriteArrayList<>();
        CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1),done=new CountDownLatch(100);
        assertEquals(RouteExecuteStatus.ACCEPTED,executor.tryExecute(1,1,() -> {
            entered.countDown(); try { assertTrue(release.await(5,TimeUnit.SECONDS)); } catch(InterruptedException e) { throw new AssertionError(e); }
        }));
        assertTrue(entered.await(5,TimeUnit.SECONDS));
        for(int i=0;i<100;i++) { int index=i; assertEquals(RouteExecuteStatus.ACCEPTED,executor.tryExecute(1,1,() -> {order.add(index);done.countDown();})); }
        CountDownLatch other=new CountDownLatch(1);
        executor.tryExecute(1,2,other::countDown); assertTrue(other.await(5,TimeUnit.SECONDS));
        executor.close(); assertEquals(RouteExecuteStatus.CLOSED,executor.tryExecute(1,1,() -> {}));
        release.countDown(); assertTrue(done.await(5,TimeUnit.SECONDS));
        assertEquals(java.util.stream.IntStream.range(0,100).boxed().toList(),order);
    }
    @ParameterizedTest @ValueSource(booleans={false,true})
    void boundedAdmissionRejectsWithoutRunning(boolean virtual) throws Exception {
        RouteExecutor executor=virtual ? new VirtualThreadRouteExecutor(2) : new StripedRouteExecutor(1,1);
        CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
        try {
            executor.tryExecute(1,1,() -> {entered.countDown();try{release.await(5,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}});
            assertTrue(entered.await(5,TimeUnit.SECONDS));
            assertEquals(RouteExecuteStatus.ACCEPTED,executor.tryExecute(1,1,() -> {}));
            assertEquals(RouteExecuteStatus.OVERLOADED,executor.tryExecute(1,1,() -> fail("Rejected task ran")));
        } finally { release.countDown(); executor.close(); }
    }
}
