package common;
import java.util.ArrayList;

public abstract class Node {

	public Node left, right;

	public abstract double csg (Float3 pt);

	public final boolean onSurface (Float3 pt) {
		return Math.abs(csg(pt)) < 1e-8;
	}

	public boolean intersects (Ray r) {	// for speed, subclasses may want to override this method.
		return intersection (r) != null;	// or could be allIntersections (r).size() > 0
	}

	public Intersection intersection (Ray r) {
		ArrayList<Intersection> ipts = allIntersections (r);
		// get closest and return it.
		return null;
	}

	public abstract ArrayList<Intersection> allIntersections (Ray r);

	// something for the plane intersection; perhaps there should be an interface that gives a closed-form curve for the plane intersection that a subset of the nodes implement.
	
	public abstract String getString ();

	public final String toString () {
		return stringify (0);
	}

	private String stringify (int depth) {
		StringBuilder sb = new StringBuilder ();
		for (int i=0; i<depth; i++) {
			sb.append ("    ");
		}
		sb.append (getString());
		if (left != null) {
			sb.append("\n");
			sb.append(left.stringify (depth+1) + "\n");
		} else {
			if (right != null) {
				sb.append ("<null>\n");
			}
		}
		if (right != null) {
			sb.append (right.stringify (depth+1) + "\n");
		}
		return sb.toString();
	}
	
}

