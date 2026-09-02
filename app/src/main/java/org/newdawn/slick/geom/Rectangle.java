package org.newdawn.slick.geom;

/** Minimal rectangle shape, matching the parts of Slick used by the engine. */
public class Rectangle extends Shape {
    protected float width;
    protected float height;

    public Rectangle(float x, float y, float width, float height) {
        this.x = x;
        this.y = y;
        this.width = width;
        this.height = height;
    }

    public float getWidth() {
        return width;
    }

    public float getHeight() {
        return height;
    }

    public void setWidth(float width) {
        this.width = width;
    }

    public void setHeight(float height) {
        this.height = height;
    }

    public void setBounds(float x, float y, float width, float height) {
        this.x = x;
        this.y = y;
        this.width = width;
        this.height = height;
    }

    public void setBounds(Rectangle other) {
        setBounds(other.x, other.y, other.width, other.height);
    }

    public void setSize(float width, float height) {
        this.width = width;
        this.height = height;
    }

    @Override
    public boolean contains(float px, float py) {
        return px >= x && py >= y && px < x + width && py < y + height;
    }

    @Override
    public boolean intersects(Shape other) {
        if (other instanceof Rectangle) {
            Rectangle r = (Rectangle) other;
            return r.x < x + width && r.x + r.width > x && r.y < y + height && r.y + r.height > y;
        }
        return other.contains(x, y) || other.contains(x + width, y + height);
    }

    @Override
    public String toString() {
        return "Rectangle[x=" + x + ",y=" + y + ",w=" + width + ",h=" + height + "]";
    }
}
