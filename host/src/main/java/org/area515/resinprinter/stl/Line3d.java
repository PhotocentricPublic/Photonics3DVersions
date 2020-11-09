package org.area515.resinprinter.stl;

import java.awt.geom.Line2D;


public class Line3d implements Shape3d {
	private Point3d one;
	private Point3d two;
	private Point3d normal;
	private Face3d originatingFace;//This is usually a Triangle3d
	private double slope;
	private double xintercept;
	
	public Line3d(Point3d one, Point3d two, Point3d normal, Face3d originatingFace, boolean swapIfNecessary) {
		if (!swapIfNecessary || one.x < two.x) {
			this.one = one;
			this.two = two;
		} else {
			this.one = two;
			this.two = one;
		}

		this.originatingFace = originatingFace;
		this.normal = normal != null?normal:new Point3d(this.one.y - this.two.y, this.one.x - this.two.x, this.two.z - this.one.z);//Uses counterclockwise rule in coop with Triangle3d creation in ZSlicer constructor
		this.slope = (this.one.x - this.two.x) / (this.one.y - this.two.y);
		this.xintercept = -(slope * this.one.y - this.one.x);
	}
	
	public boolean intersects(double x1, double y1, double x2, double y2) {
		return Line2D.linesIntersect(x1, y1, x2, y2, one.x, one.y, two.x, two.y);
	}
	
	public double getXIntersectionPoint(double y) {
		return slope * y + xintercept;
	}
	
	public double getMinX() {
		return Math.min(one.x, two.x);
	}
	public double getMinY() {
		return Math.min(one.y, two.y);
	}
	
	public double getMaxX() {
		return Math.max(one.x, two.x);
	}
	public double getMaxY() {
		return Math.max(one.y, two.y);
	}
	
	public Point3d getNormal() {
		return normal;
	}
	
	public Point3d getPointOne() {
		return one;
	}
	
	public Point3d getPointTwo() {
		return two;
	}

	public void swap() {
		Point3d swap = one;
		this.one = two;
		this.two = swap;
	}
	
	public Face3d getOriginatingFace() {
		return originatingFace;
	}

	@Override
	public String toString() {
		return "[" + one + "," + two + "]" + (originatingFace == null?"(Artificial Line)":"");
	}

	@Override
	public int hashCode() {
		final int prime = 31;
		int result = 1;
		result = prime * result + ((one == null) ? 0 : one.hashCode());
		result = prime * result + ((two == null) ? 0 : two.hashCode());
		return result;
	}

	public boolean pointsEqual(Object obj) {
		if (this == obj)
			return true;
		if (obj == null)
			return false;
		if (getClass() != obj.getClass())
			return false;
		Line3d other = (Line3d) obj;
		if ((one == other.one || (one != null && one.pointEquals(other.one))) &&
			(two == other.two || (two != null && two.pointEquals(other.two)))) {
			return true;
		}
		if ((one == other.two || (two != null && two.pointEquals(other.one))) &&
			(two == other.one || (one != null && one.pointEquals(other.two)))) {
			return true;
		}
		return false;
	}
	
	@Override
	public boolean equals(Object obj) {
		if (this == obj)
			return true;
		if (obj == null)
			return false;
		if (getClass() != obj.getClass())
			return false;
		Line3d other = (Line3d) obj;
		if (one == null) {
			if (other.one != null)
				return false;
		} else if (!one.equals(other.one))
			return false;
		if (two == null) {
			if (other.two != null)
				return false;
		} else if (!two.equals(other.two))
			return false;
		return true;
	}
}
