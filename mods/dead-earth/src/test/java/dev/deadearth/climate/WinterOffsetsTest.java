package dev.deadearth.climate;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import static org.junit.jupiter.api.Assertions.*;

@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class WinterOffsetsTest {
    private record Point(int dx,int dz) {}
    private record Expected(int dx,int dz,int distance,String face) {}

    @Test @Order(1) void repeatedRadiusReusesAnImmutableList() throws ReflectiveOperationException {
        var first=offsets(3);
        assertSame(first,offsets(3),"Repeated columns must reuse the same offset list");
        for(int radius=0;radius<=10;radius++) {
            var values=offsets(radius);
            assertSame(values,offsets(radius),"Missing cached radius "+radius);
            assertThrows(UnsupportedOperationException.class,()->values.add(null),"Shared offsets must reject additions");
            assertThrows(UnsupportedOperationException.class,values::clear,"Shared offsets must reject clearing");
        }
    }

    @Test @Order(2) void anchorTraversalAndDiagonalFaceTieBreakStayUnchanged() throws ReflectiveOperationException {
        var expected=List.of(
                new Expected(-1,0,1,"EAST"),new Expected(0,1,1,"NORTH"),
                new Expected(0,-1,1,"SOUTH"),new Expected(1,0,1,"WEST"),
                new Expected(-2,0,2,"EAST"),new Expected(-1,1,2,"EAST"),
                new Expected(-1,-1,2,"EAST"),new Expected(0,2,2,"NORTH"),
                new Expected(0,-2,2,"SOUTH"),new Expected(1,1,2,"WEST"),
                new Expected(1,-1,2,"WEST"),new Expected(2,0,2,"WEST"));
        var actual=offsets(2);
        assertEquals(expected.size(),actual.size());
        for(int n=0;n<expected.size();n++) {
            var want=expected.get(n);var value=actual.get(n);
            assertEquals(new Point(want.dx(),want.dz()),point(value),"Anchor preference changed at index "+n);
            // The baseline computes this metadata in plan(); the cache stores it on each record.
            if(!(value instanceof int[])) {
                assertEquals(want.distance(),access(value,"distance"));
                assertEquals(want.face(),((Enum<?>)access(value,"face")).name());
            }
        }
    }

    @Test @Order(3) void eachAllowedRadiusVisitsItsCompleteDiamondOnceInTheOriginalOrder() throws ReflectiveOperationException {
        var order=Comparator.comparingInt((Point p)->Math.abs(p.dx())+Math.abs(p.dz()))
                .thenComparingInt(Point::dx).thenComparing(Comparator.comparingInt(Point::dz).reversed());
        for(int radius=0;radius<=10;radius++) {
            var points=new ArrayList<Point>();
            for(var value:offsets(radius)) {
                var p=point(value);int distance=Math.abs(p.dx())+Math.abs(p.dz());
                assertTrue(distance>=1&&distance<=radius,"Offset exceeds configured reach");
                if(!(value instanceof int[]))assertEquals(distance,access(value,"distance"));
                points.add(p);
            }
            assertEquals(2*radius*(radius+1),points.size(),"Incomplete diamond for radius "+radius);
            assertEquals(points.size(),new HashSet<>(points).size(),"Duplicate anchor offsets");
            var sorted=new ArrayList<>(points);sorted.sort(order);
            assertEquals(sorted,points,"Traversal order changed for radius "+radius);
        }
    }

    private static List<?> offsets(int radius) throws ReflectiveOperationException {
        Method method=WinterCoating.class.getDeclaredMethod("offsets",int.class);method.setAccessible(true);
        return (List<?>)method.invoke(null,radius);
    }
    private static Point point(Object offset) throws ReflectiveOperationException {
        if(offset instanceof int[] old)return new Point(old[0],old[1]);
        return new Point((Integer)access(offset,"dx"),(Integer)access(offset,"dz"));
    }
    private static Object access(Object value,String name) throws ReflectiveOperationException {
        Method method=value.getClass().getDeclaredMethod(name);method.setAccessible(true);return method.invoke(value);
    }
}
