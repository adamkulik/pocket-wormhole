package org.newdawn.slick;

public interface InputListener extends ControlledInputReciever {
    void keyPressed(int key, char c);

    void keyReleased(int key, char c);

    void mouseMoved(int oldx, int oldy, int newx, int newy);

    void mouseDragged(int oldx, int oldy, int newx, int newy);

    void mouseClicked(int button, int x, int y, int clickCount);

    void mousePressed(int button, int x, int y);

    void mouseReleased(int button, int x, int y);

    void mouseWheelMoved(int change);

    void controllerLeftPressed(int controller);

    void controllerLeftReleased(int controller);

    void controllerRightPressed(int controller);

    void controllerRightReleased(int controller);

    void controllerUpPressed(int controller);

    void controllerUpReleased(int controller);

    void controllerDownPressed(int controller);

    void controllerDownReleased(int controller);

    void controllerButtonPressed(int controller, int button);

    void controllerButtonReleased(int controller, int button);
}
