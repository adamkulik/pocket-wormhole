package org.newdawn.slick;

public interface ControlledInputReciever {
    void setInput(Input input);

    boolean isAcceptingInput();

    void inputStarted();

    void inputEnded();
}
