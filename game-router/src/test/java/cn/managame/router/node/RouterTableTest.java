package cn.managame.router.node;

import cn.managame.router.route.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RouterTableTest {
    @Test void removalAndOwnerCheckedUnbindPreserveOtherNodesAndAllowNewIncarnation() {
        RouterTable table = new RouterTable();
        table.add(new NodeRegistration(1, 7, 1)); table.add(new NodeRegistration(2, 8, 1));
        BindingKey one = new BindingKey(1, 10), two = new BindingKey(1, 20);
        RouteBinding a = new RouteBinding(1, 7), b = new RouteBinding(2, 8);
        table.bind(one, a); table.bind(one, a); table.bind(two, b);
        table.unbind(one, b); table.remove(1, 99); assertEquals(a, table.bindings.get(one));
        table.remove(1, 7); assertFalse(table.bindings.containsKey(one)); assertEquals(b, table.bindings.get(two));
        table.add(new NodeRegistration(1, 9, 1)); table.bind(one, new RouteBinding(1, 9));
        table.unbind(one, a); assertTrue(table.bindings.containsKey(one));
        table.unbind(one, new RouteBinding(1, 9)); table.bind(one, new RouteBinding(1, 9));
        table.remove(1, 9); assertFalse(table.bindings.containsKey(one));
        table.clear(); assertTrue(table.nodes.isEmpty()); assertTrue(table.bindings.isEmpty());
    }
}
