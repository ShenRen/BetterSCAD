package frontend;
import common.*;
import java.util.*;
public class Interpreter {

	/* This class takes the parse tree after semantics has constructed the static symbol tables and
	 * runs it, producing our internal representation. */

	private Tree root;
	private Stack<Bigframe> rts;

	public Interpreter (Tree t) {
		root = t;
		rts = new Stack <Bigframe> ();
		// push a Bigframe that has the predefined variables in it.
		Bigframe b = new Bigframe ("ROOT");
		for (Map.Entry<String, STE> e : t.st.parent.vars.entries.entrySet()) {
			System.err.println("Adding " + e.getKey() + " to bigframe");
			b.put (e.getKey(), evalExpr (((STE) e.getValue()).t));	// these will actually just be constants
		}
		rts.push(b);
		// now run the tree.
	}

	private void popSmall () {
		Bigframe b = rts.peek();
		if (!b.stack.isEmpty()) {
			b.stack.pop();
		}
	}

	private Datum findVar (String name) {
		Bigframe top = rts.peek();
		Datum d = top.findDeep (name);	// always do a full search of the top bigframe.

		int i = rts.size() - 2;
		while (d.isUndef () && i >= 0) {
			if (name.charAt(0) == '$') {	// special variable
				d = rts.get(i).findDeep (name);
			} else {
				d = rts.get(i).findShallow (name);
			}
			i--;
		}
		return d;
	}

	private Datum evalExpr (Tree t) {
		if (t.type == Treetype.FLIT) {
			return new Scalar ((Double) t.data);
		} else if (t.type == Treetype.SLIT) {
			return new Str (t.name());
		} else if (t.type == Treetype.UNDEF) {
			return new Undef();
		} else if (t.type == Treetype.VECTOR) {
			Vec v = new Vec ();
			for (Tree ch : t.children) {
				v.vals.add (evalExpr (ch));
			}
			return v;
		} else if (t.type == Treetype.OP) {
			return ((Op) t.data).eval (evalExpr (t.children.get(0)), t.children.size() > 1 ? evalExpr(t.children.get(1)) : null);
		} else if (t.type == Treetype.FCALL) {
			return runFcall (t);
		} else if (t.type == Treetype.IDENT) {
			return findVar (t.name());
		} else {
			System.err.println ("Uh oh, bad tree type in evalExpr: " + t);
			Thread.currentThread().dumpStack();
			System.exit(1);
		}
		return null;
	}

	public Node run () {
		return run (root);
	}

	private void printStack () {
		for (int i=0; i<rts.size(); i++) {
			rts.get(i).print();
		}
	}

	private Node run (Tree t) {
		System.out.println ("RUNNING node. type = " + t.type);
		printStack();
		Thread.currentThread().dumpStack();
		/* FIXME: for IFs and FORs we need to do stuff to the stack */
		if (t.type == Treetype.IF) {
			for (Tree ch : t.children) {	// the CONDITION trees
				if (evalExpr (ch.children.get(0)).isTrue()) {
					return run (ch.children.get(1));
				}
			}
			return null;
		} else if (t.type == Treetype.FOR || t.type == Treetype.INTFOR) {
			ArrayList<Node> res = new ArrayList<Node>();

		} else if (t.type == Treetype.ASSIGN) {
			/* This should only be run if we're doing runtime assignment. */
			if (Prefs.current.RUNTIME_VARS) {
				rts.peek().put (t.name(), evalExpr (t.children.get(0)));
			}
		} else if (t.type == Treetype.MCALL) {
			return runMcall (t);
		} else if (t.type == Treetype.ROOT) {
			Bigframe b = new Bigframe ("Root");
			for (Map.Entry<String, STE> e : t.st.vars.entries.entrySet()) {
				if (Prefs.current.RUNTIME_VARS) {
					b.base.entries.put (e.getKey(), new Undef());
				} else {
					b.base.entries.put (e.getKey(), evalExpr (e.getValue().t));
				}
			}
			rts.push (b);
			return makeExplicit (runList (t.children, 0), CSG.UNION);
		} else if (t.type == Treetype.MODULE || t.type == Treetype.FUNCTION) {
			// skip over.
		} else {
			System.err.println ("Bad tree type in run. " + t);
			System.exit(1);
		}
		return null;
	}

	/* Interpret a list of trees, starting at index 'start' */
	private ArrayList<Node> runList (ArrayList<Tree> t, int start) {	
		ArrayList<Node> ch = new ArrayList<Node>();
		for (int i=start; i<t.size(); i++) {
			Node n = run (t.get(i));
			if (n != null) {	// it would be null if, for example, the node was an assignment or a definition
				ch.add (n);
			}
		}
		return ch;
	}

	/* Creates a smallframe for the tree t and pushes it onto the top of the top bigframe's stack. 
	 * t should have a static symbol table associated with it. 
	 *
	 * If RUNTIME_VARS is true, these symbols are entered into the table but initialized to undef
	 * If RUNTIME_VARS if false, their trees from the static ST are evaluated and then they are entered.
	*/
	private void createSmallframe (Tree t) {
		Smallframe sf = new Smallframe (t.type.toString());
		if (Prefs.current.RUNTIME_VARS) {
			for (String vname : t.st.vars.entries.keySet()) {
				sf.entries.put (vname, new Undef());
			}
		} else {
			for (Map.Entry<String, STE> e : t.st.vars.entries.entrySet ()) {
				sf.entries.put (e.getKey(), evalExpr (e.getValue().t));
			}
		}
		rts.peek().stack.push (sf);
	}

	// According to the parameter profile in stat (corresponds to a module or function definition), 
	// add the parameters in plist to the bigframe's base. stat is needed to resolve the names
	// of positional parameters.
	private void populateParameters (Bigframe b, Tree stat, Tree plist, boolean predef) {
		int pos = 0;

		// first initialize the defaults
		for (Tree ch : stat.children) {
			if (!ch.children.isEmpty()) {	// has a default value
				b.base.entries.put (ch.name(), evalExpr (ch.children.get(0)));
			} else {
				// and we do want this branch, since the parameter name should shadow locals of the 
				// same name in the enclosing scope, even if the parameter itself was not passed
				b.base.entries.put (ch.name(), new Undef());
			}
		}

		// now go through the given parameter list.
		for (Tree ch : plist.children) {
			if (ch.type == Treetype.PARAM) {	// named
				b.base.entries.put (ch.name(), evalExpr (ch.children.get(0)));
			} else {
				if (pos < stat.children.size()) {
					Datum d = evalExpr (ch);
					if (d instanceof Undef) {
						int klass = predef ? 1 : 2;
						if (klass <= Prefs.current.PROTECT_FROM_UNDEF) {
							continue;
						}
					}
					b.base.entries.put (stat.children.get(pos).name(), d);
				}
				pos++;
			}
		}
	}

	private void populateStaticallyEnclosingLocals (Bigframe b, Tree t) {
		if (t.st.parent == null) return;	// this will happen for the predefined modules
		STSet pset = t.st.parent;
		while (pset.tree.type != Treetype.MODULE && pset.tree.type != Treetype.ROOT) {
			for (String vname : pset.vars.entries.keySet()) {
				b.base.entries.put (vname, findVar (vname));	// we use findVar since we might
				// be using runtime variables, and we want the actual current value.
			}
			pset = pset.parent;
			if (pset == null) break;
		}
	}

	/* Some terminology about modules: the module's parameters are what get passed in parentheses. 
	 * The module's children or body is the stuff that gets passed in braces.
	 * The module's definition is the actual code associated with the module. */

	private Node runMcall (Tree mc) {
		System.out.println ("Running mcall " + mc.name());
		/* To run a module: first, get the Node that represents the children. This will involve pushing
		 * a smallframe (so that local variables to the module body are scoped properly) and recursing.
		 *
		 * Pop this subframe, then evaluate the parameters. Now, push a new bigframe, whose base is populated
		 * with the parameters and copies of the variables from the statically enclosing scope (which may be 
		 * in smallframes since the module definition can be nested inside other constructs). 
		 * Now run the module definition. In some (many) cases, this will be one of the predefined modules 
		 * (union, linear_extrude, etc.)
		*/

		createSmallframe (mc);
		System.out.print ("The top smallframe is: ");
		rts.peek().stack.peek().print();
		System.out.println();

		ArrayList<Node> children = runList (mc.children, 1);	// don't run the parameters list (0th child)
		popSmall();

		Bigframe b = new Bigframe (mc.name() + "_def");
		Tree mdef = mc.st.modules.findSTE(mc.name()).t;
		// now we need to copy the variables from the statically enclosing scope into b.base. 
		// This means we just need to copy any locals in statically enclosing smallframes, 
		// since everything else will still be accessible.
		populateStaticallyEnclosingLocals (b, mdef);

		// the parameters get added in, shadowing any variables already present of the same name
		STSet st = mdef.findST();
		boolean predef = st.parent == null;
		populateParameters (b, mdef.children.get(0), mc.children.get(0), predef);

		// then we can push the new bigframe and go off and execute the module code.
		rts.push (b);

		Node result;
		if (predef) {
			result = runPredefModule (mdef, children);
		} else {
			result = runUserModule (mdef, children);
		}
		rts.pop();
		return result;
	}

	/* This will be in many ways similar to runMcall, except there is no child evaluation */
	private Datum runFcall (Tree fc) {
		Bigframe b = new Bigframe (fc.name());
		populateStaticallyEnclosingLocals (b, fc);
		Tree fdef = fc.st.functions.findSTE(fc.name()).t;
		populateParameters (b, fdef, fc.children.get(0), false);	// is false the right value to pass for this?
		rts.push(b);
		STSet st = fdef.findST();

		Datum res;
		if (st.parent == null) {	// function in the runtimes
			res = runPredefFunc (fdef);
		} else {
			res = evalExpr (fdef.children.get(1));
		}
		rts.pop();
		return res;
	}

	private Datum runPredefFunc (Tree fdef) {
		String n = fdef.name();
		// TODO: Implement me!
		return new Undef();
	}
/*
		String[] modules = {"union", "intersection", "difference", "assign", "square", "circle", "polygon", "cube", "cylinder", "sphere", "linear_extrude", "rotate_extrude", "translate", "scale", "rotate", "mirror", "multmatrix", "color"};
		String[][] mparam = {{}, {}, {}, {}, {"size", "center"}, {"r", "center"}, {"points", "paths", "convexity"}, {"size", "center"}, {"r", "center"}, {"height", "center", "convexity", "twist", "slices", "scale"}, {"convexity"}, {"v"}, {"v"}, {"a", "v"}, {"v"}, {"m"}, {"c", "alpha"}};
		*/
	private Node runPredefModule (Tree mdef, ArrayList<Node> children) {
		String n = mdef.name();
		if (n.equals("union")) {
			return makeExplicit (children, CSG.UNION);
		} else if (n.equals ("intersection")) {
			return makeExplicit (children, CSG.INTERSECTION);
		} else if (n.equals ("difference")) {
			Node u = new CSG (CSG.DIFFERENCE);
			u.left = children.get(0);
			children.remove (0);
			u.right = makeExplicit (children, CSG.UNION);
			return u;
		} else if (n.equals ("linear_extrude")) {
			// TODO: get the parameters in; may involve creating a transform for a tapered extrusion, or whatever we do for twisted extrudes.`
			Datum h = findVar ("height");
			if (! (h instanceof Scalar)) {
				return null;
			}
			Node e = new Extrude (((Scalar) h).d);
			e.left = makeExplicit (children, CSG.UNION);
			return e;
		} else if (n.equals ("rotate_extrude")) {
			Node r = new Revolve ();
			r.left = makeExplicit (children, CSG.UNION);
			return r;
		} else if (n.equals ("sphere")) {
			Datum r = findVar ("r");
			if (r instanceof Scalar) {
				return new Sphere (((Scalar) r).d);
			} else {
				System.err.println ("ERROR: non-scalar sphere radius");
			}
			return null;
		/*
		} else if (n.equals ("square")) {
		} else if (n.equals ("polygon")) {
		} else if (n.equals ("circle")) {
		} else if (n.equals ("cube")) {
		} else if (n.equals ("cylinder")) {
		} else if (n.equals ("translate")) {
		} else if (n.equals ("scale")) {
		} else if (n.equals ("rotate")) {
		} else if (n.equals ("mirror")) {
		} else if (n.equals ("multmatrix")) {
		} else if (n.equals ("color")) {
		*/
		} else {
			System.err.println ("Unsupported or erroneous module: " + n);
			Thread.currentThread().dumpStack();
			System.exit(1);
		}
		return null;

	}

	private Node runUserModule (Tree mdef, ArrayList<Node> children) {
		rts.peek().base.entries.put ("$children", new Scalar (children.size()));

		// run the code
		ArrayList<Node> result = runList (mdef.children, 1);
		Node res = makeExplicit (result, CSG.UNION);
		rts.pop();
		return res;
	}

	/* Makes implicit unions or intersections between a list of nodes explicit. 
	 * The 'type' parameter corresponds to values defined in common/CSG.java */
	private Node makeExplicit (ArrayList<Node> nodes, int type) {
		if (nodes.isEmpty()) return null;
		if (nodes.size() == 1) return nodes.get(0);
		CSG top = new CSG (type);
		CSG c = top;
		int i = 0;
		while (i < nodes.size() - 2) {
			c.left = nodes.get(i);
			CSG n = new CSG (type);
			c.right = n;
			c = n;
			i++;
		}
		c.left = nodes.get(i);
		c.right = nodes.get(i+1);
		return top;
	}

}

