package frontend;

import java.util.HashMap;
import java.util.Map;
public class STSet {	

	private static int NUM = 0;
	
	// A set of three symbol tables, one for modules, one for functions, and one for variables.
	// In some cases, the module and function tables won't be used.
	
	public Symtable modules, functions, vars;
	public STSet parent;
	public int id;

	public void resetID () {	// only use when restarting the compilation process.
		NUM = 0;
	}

	public STSet (STSet parent) {
		System.err.println ("Creating a new STSet. Called from: ");
		Thread.currentThread().dumpStack();
		this.parent = parent;
		modules = new Symtable();
		functions = new Symtable ();
		vars = new Symtable();
		id = ++NUM;
	}

	public Symtable getMatchingParent (Symtable s) {
		if (s == modules) return parent.modules;
		if (s == functions) return parent.functions;
		if (s == vars) return parent.vars;
		return null;
	}

	public String toString () {
		return "modules: " + modules + "  functions: " + functions + "  vars: " + vars;
	}

	public class Symtable {

		public HashMap <String, STE> entries;

		public Symtable () {
			entries = new HashMap <String, STE> ();
		}

		public void put (String name, Tree t) {
			System.err.println ("Putting " + name + " into the symbol table #" + id + " with tree\n" + t + "----");
			// first we check the tree to make sure that all of the symbols have been defined. 
			if (!depsOK (t)) {
				Semantics.error ("Failed dependency check for tree \n" + t);
			}
			entries.put (name, new STE(t));
		}

		/* Check whether all of the variables in the tree 't' have been defined at this point. */
		public boolean depsOK (Tree t) {
			if (t == null) return true;
			if (t.type == Treetype.FUNCTION || t.type == Treetype.MODULE) return true;
			if (t.type == Treetype.OP) {
				for (Tree ch : t.children) {
					if (!depsOK (ch)) return false;
				}
				return true;
			} else if (t.type == Treetype.IDENT) {
				return findVar ((String) t.data);
			} else if (t.type == Treetype.MCALL || t.type == Treetype.FCALL) {
				Tree plist = t.children.get(0);
				for (Tree param : plist.children) {
					if (param.type == Treetype.PARAM) {
						if (!depsOK (param.children.get(0))) return false;
					} else {
						if (!depsOK (param)) return false;
					}
				}
				return true;
			} else if (t.type == Treetype.VECTOR) {
				for (Tree e : t.children) {
					if (!depsOK (e)) return false;
				}
				return true;
			} else {	// a literal
				return true;
			}
		}

		public boolean findVar (String name) {
			System.err.print ("Trying to find " + name + " in ST #" + id + ". The entry set is <");
			for (String s : entries.keySet()) {
				System.err.print (s + ", ");
			}
			System.err.println (">");
			if (entries.containsKey (name)) return true;
			if (parent != null) {
				return getMatchingParent(this).findVar (name);
			}
			return false;
		}

		public STE findSTE (String name) {
			if (entries.containsKey (name)) return entries.get(name);
			if (parent != null) {
				return getMatchingParent(this).findSTE (name);
			}
			return null;
		}

		public String toString () {
			StringBuilder sb = new StringBuilder("<");
			for (Map.Entry e : entries.entrySet()) {
				sb.append (e.getKey());
				Tree t = ((STE) e.getValue()).t;
				if (t != null) {
					if (t.type == Treetype.FLIT) {
						sb.append (" = " + (Double) t.data);
					} else if (t.type == Treetype.IDENT) {
						sb.append (" = " + t.name());
					} else {
						sb.append (" = ...");
					}
				}
				sb.append (", ");
			}
			sb.append (">");
			return sb.toString();
		}


	}
}