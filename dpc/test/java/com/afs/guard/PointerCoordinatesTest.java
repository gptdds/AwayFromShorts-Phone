package com.afs.guard;

import com.afs.guard.style.PointerCoordinates;

public final class PointerCoordinatesTest {
    private static int checks;
    private static void near(float expected,float actual){
        checks++;
        if(Math.abs(expected-actual)>0.0001f)throw new AssertionError(expected+" != "+actual);
    }
    public static void main(String[] args){
        near(36,PointerCoordinates.screen(24,12,1));
        near(36,PointerCoordinates.screen(12,12,2));
        near(24,PointerCoordinates.screen(24,0,0));
        near(-1,PointerCoordinates.normalized(10,20,100));
        near(0,PointerCoordinates.normalized(70,20,100));
        near(.6f,PointerCoordinates.normalized(100,20,100));
        near(1,PointerCoordinates.normalized(200,20,100));
        near(-1,PointerCoordinates.normalized(0,0,0));
        System.out.println("Pointer coordinates: "+checks+" checks passed");
    }
}
