package xyz.znix.xftl.math

/**
 * A 3x3 float matrix, useful for 2D affine transforms.
 *
 * Similarly to how 3D can use 4x4 matrices, this size lets us transform points
 * about the origin, and the 3rd column lets us translate points by representing
 * them as x,y,w (w=1) vectors.
 */
class Matrix3f() {
    // The members, named with a m<row><column> naming scheme.
    // This is effectively m<y><x> if you used x,y indices into the matrix.

    var m00 = 1f
    var m10 = 0f
    var m20 = 0f

    var m01 = 0f
    var m11 = 1f
    var m21 = 0f

    var m02 = 0f
    var m12 = 0f
    var m22 = 1f

    constructor(other: Matrix3f) : this() {
        m00 = other.m00
        m10 = other.m10
        m20 = other.m20

        m01 = other.m01
        m11 = other.m11
        m21 = other.m21

        m02 = other.m02
        m12 = other.m12
        m22 = other.m22
    }
}
