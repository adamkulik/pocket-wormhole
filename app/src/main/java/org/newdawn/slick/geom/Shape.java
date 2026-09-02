package org.newdawn.slick.geom;

/** Minimal base shape. */
public abstract class Shape {
    protected float x;
    protected float y;

    public float getX() {
        return x;
    }

    public float getY() {
        return y;
    }

    public void setX(float x) {
        this.x = x;
    }

    public void setY(float y) {
        this.y = y;
    }

    public abstract boolean contains(float x, float y);

    public abstract boolean intersects(Shape other);
}
