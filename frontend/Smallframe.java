package frontend;
import java.util.*;
public class Smallframe {

	public HashMap <String, Datum> entries;
	public String name;	// this is really just for debugging purposes

	public Smallframe (String name) {
		this.name = name;
		entries = new HashMap <String, Datum>();
	}

	public Datum find (String name) {
		Datum d = entries.get (name);
		if (d == null) {
			return new Undef();
		}
		return d;
	}

	public void print () {
		System.out.print ("(" + name + "): ");
		for (Map.Entry<String, Datum> e : entries.entrySet()) {
			System.out.print (e.getKey() + " = " + e.getValue() + ", ");
		}
	}


}
	
