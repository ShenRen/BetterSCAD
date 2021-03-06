package frontend;

import java.util.ArrayList;
import java.util.Arrays;
import java.io.*;
public class Parser {

	private ArrayList<Token> tokens;
	private int i;
	private File sourcefile;
	private File sourcedir;
	private int nesting = 0;

	private static ArrayList<String> includes = new ArrayList<String>();

	public static void reset () {
		includes.clear();
	}

	public Parser (File f, String str, boolean from_file) throws ParseException {
		/* If from_file is true, it will read from the file, otherwise
		 * it will use the given string. The file is still used to determine
		 * relative include paths */
		if (f != null) includes.add (f.getAbsolutePath());
		Lexer lex = null;
		if (from_file) {
	  		lex = new Lexer (f);
		} else {
			lex = new Lexer (str);
		}
		tokens = lex.tokenize();
		tokens.add (new Token (Tokentype.EOF, new FileMark (-1, -1, -1)));
		int ct = 0;
		for (Token t : tokens) {
			System.out.println (ct + ": " + t);
			ct ++;
		}
		i = 0;
		if (f != null) {
			sourcefile = f.getAbsoluteFile();
			sourcedir = new File (sourcefile.getParent());
		} else {
			sourcedir = new File ("./");
		}
	}

	public void error (String message) throws ParseException{
		throw new ParseException ("Parser ERROR in line " + tokens.get(i).fm + "(token " + i + "): " + message, tokens.get(i).fm);
	}

	public void error (String message, FileMark fm) throws ParseException{
		throw new ParseException ("Parser ERROR in line " + fm + ": " + message, fm);
	}

	public void nferror (String message) {
		// TODO: append this to the current console
		System.err.println ("Parser nonfatal ERROR in line " + tokens.get(i).fm + "(token " + i + "): " + message);
		Thread.currentThread().dumpStack();
	}

	public boolean has (int x) {
		return tokens.size () - 1 - x > i;
	}

	public Token next () throws ParseException{
		if (has (0)) {
			return tokens.get(++i);
		}
		error ("Reached end of file");
		return null;
	}

	public Token peek () {
		if (has(0)) {
			return tokens.get(i);
		}
		return tokens.get(tokens.size()-1);	// which will be the EOF token.
	}

	public Token prev () {
		if (i >= 1) {
			return tokens.get(--i);
		}
		return null;
	}

	public File findSource (String name) {	// for resolving include and use statements
		// first look in the same directory as the SCAD source file
		File f = new File (sourcedir + "/" + name);
		if (f.exists()) {
			return f;
		} else {
			// treat it as an absolute pathname
			f = new File (name);
			if (f.exists()) return f;
		}
		return null;
	}

	public Tree parse () throws ParseException{	// top-level parsing routine
		Tree root = new Tree (Treetype.ROOT, peek().fm);
		root.nest_depth = 0;
		while (peek().type != Tokentype.EOF) {
			if (peek().is ("module")) {
				root.addChild (parseModule());
			} else if (peek().is ("function")) {
				root.addChild (parseFunction ());
			} else if (peek().is ("include")) {
				File f = findSource (next().val);
				if (f == null) {
					nferror ("Could not load '" + peek().val + "'.");
				} else if (includes.contains(f.getAbsolutePath())) {
					nferror ("Include cycle detected with file " + peek().val);
				} else {
					Tree t = new Parser (f, null, true).parse();
					root.addChildAll (t.children);
				}
				next();
			} else if (peek().is ("use")) {
				File f = findSource (next().val);
				if (f == null) {
					nferror ("Could not load '" + peek().val + "'.");
				} else if (includes.contains(f.getAbsolutePath())) {
					nferror ("Include cycle detected with file " + peek().val);
				} else {
					Tree subfile = new Parser(f, null, true).parse();
					for (int q=0; q<subfile.children.size(); q++) {
						Tree ch = (Tree) subfile.children.get(q);	// for some reason the compiler thinks that the contents of the children list are Objects, not Trees.
						if (ch.type == Treetype.MODULE || ch.type == Treetype.FUNCTION) {
							root.addChild (ch);
						}
					}
				}
				next();
			} else {
				root.addChild (parseStatement());
			}
		}
		return root;
	}

	public Tree parseModule () throws ParseException{
		/* The tree's data is the name of the module.
		 * Child 0 contains the parameter profile.
		 * Child 1 contains the module body.
		*/
		nesting += 1;
		Tree res = new Tree (Treetype.MODULE, peek().fm);
		res.nest_depth = nesting;
		if (peek().is("module")) {
			next();
			if (peek().type == Tokentype.IDENT) {
				res.data = peek().val;
				next();
				if (peek().type == Tokentype.OPEN_PAREN) {
					res.addChild (parseParamlist());
				} else {
					error ("Expecting open parenthesis in module declaration, found " + peek());
				}
				if (peek().type == Tokentype.OPEN_BRACE) {
					next();
					// now for the body of the module
					while (peek().type != Tokentype.CLOSE_BRACE) {
						if (peek().is ("module")) {
							res.addChild (parseModule());
						} else if (peek().is ("function")) {
							res.addChild (parseFunction());
						} else {
							res.addChild (parseStatement());
						}
					}
					next();	// skip the close brace
				} else {
					error ("Expecting { after module profile, found " + peek());
				}
			} else {
				error ("Expecting name after 'module' keyword, found " + peek());
			}
		} else {
			error ("Expecting module to start with 'module' keyword");
		}
		nesting -= 1;
		return res;
	}

	public Tree parseFunction () throws ParseException{
		/* The tree's data is the name of the function. 
		 * Child 0 is a tree that has the parameter profile.
		 * Child 1 is the function body.
		*/
		Tree res = new Tree(Treetype.FUNCTION, peek().fm);
		res.nest_depth = nesting+1;	// no need to actually increment 'nesting' here, since nothing can nest inside of functions
		if (peek().is ("function")) {
			next ();
			if (peek().type == Tokentype.IDENT) {
				res.data = peek().val;
				next();
				if (peek().type == Tokentype.OPEN_PAREN) {
					res.addChild (parseParamlist());
				} else {
					error ("Expecting open parenthesis in function declaration, found " + peek());
				}
				if (peek().type == Tokentype.ASSIGN) {
					next ();
					res.addChild (parseExpr());
				} else {
					error ("Expecting assignment after function prototype, found " + peek());
				}
			} else {
				error ("Expecting identifier after 'function' keyword, found " + peek());
			}
		} else {
			error ("Expecting 'function' keyword");
		}
		if (peek().type == Tokentype.SEMICOLON) {
			next();
		} else {
		       error ("Missing semicolon after function declaration");
		}	       
		return res;
	}

	public Tree parseCall (Treetype calltype) throws ParseException{
		/* Function call trees have the following format:
		 * Data: name of function
		 * Child 0: PARAMLIST tree
		 * Child 1: body
		 *
		 * The paramlist tree goes like this:
		 * The children are either just expressions (for positional parameters), or are PARAM nodes, which have the var name as data and the expr as a child.
		*/

		nesting += 1;
		Tree res = new Tree (calltype, peek().fm);
		res.nest_depth = nesting;
		if (peek().type == Tokentype.IDENT) {
			res.data = peek().val;
			next();
			if (peek().type != Tokentype.OPEN_PAREN) {
				error ("Expecting open parenthesis after identifier, found " + peek());
			}
			next();
			Tree plist = new Tree (Treetype.PARAMLIST, peek().fm);
			res.addChild (plist);

			while (true) {
				if (peek().type == Tokentype.COMMA) {
					next();
				} else if (peek().type == Tokentype.CLOSE_PAREN) {
					next();
					break;
				} else {
					if (peek().type == Tokentype.IDENT) {
						String name = peek().val;
						if (next().type == Tokentype.ASSIGN) {	// then we're assigning a named parameter.
							Tree param = new Tree (Treetype.PARAM, peek().fm);
							param.data = name;
							next();
							param.addChild (parseExpr());
							plist.addChild (param);
						} else {
							prev();
							plist.addChild (parseExpr());
						}
					} else {
						plist.addChild (parseExpr());
					}
				}
			}
		} else {
			error ("Expecting identifier to begin function call");
		}

		/* Convert assign blocks into unions with assignment statements (actually declares) inside them. */
		if (calltype == Treetype.MCALL) {
			if (res.name().equals("assign")) {
				res.data = "union";
				Tree plist = res.children.get(0);
				res.children.remove(0);	// remove the plist and just leave the body.
				for (int i=0; i<plist.children.size(); i++) {
					Tree param = plist.children.get(i);
					if (param.type != Treetype.PARAM) {	// that is, it was given positionally
						error ("Positional parameters not allowed in assign blocks", param.fm);
					}
					Tree as = new Tree (Treetype.DECLARE, param.name(), param.fm);
					as.nest_depth = res.nest_depth;
					as.addChild (param.children.get(0));
					res.children.add(0, as);
				}
			}
		}
		nesting -= 1;
		return res;
	}

	public Tree parseVectorOrRange () throws ParseException {
		int savepoint = i;
		if (peek().type == Tokentype.OPEN_BRACKET) {
			next();
			Tree expr1 = parseExpr();
			/* Ranges can be either [start:end] or [start:increment:end]. We store them in this order:
			 * child 0 : start
			 * child 1 : end
			 * child 2 : increment
			 */
			if (peek().type == Tokentype.COLON) {	// range
				Tree range = new Tree (Treetype.RANGE, peek().fm);
				range.addChild (expr1);
				next();
				Tree expr2 = parseExpr();
				if (peek().type == Tokentype.COLON) {	// then we have the 3-element form of RANGE.
					next();
					Tree expr3 = parseExpr();
					range.addChild (expr3);
					range.addChild (expr2);
				} else {
					range.addChild (expr2);
					range.addChild (new Tree (Treetype.FLIT, 1.0, peek().fm));
				}

				if (peek().type == Tokentype.CLOSE_BRACKET) {
					next();
				} else {
					nferror ("Expecting close bracket ] end range, found " + peek());
				}
				return range;
			} else {	// vector
				i = savepoint;
				return parseVector();
			}
		} else {
			error ("Expecting either range or vector, found " + peek());
		}
		return null;
	}

	public Tree parseVector () throws ParseException{
		Tree vec = new Tree (Treetype.VECTOR, peek().fm);
		if (peek().type == Tokentype.OPEN_BRACKET) {
			next();
			while (true) {
				if (peek().type == Tokentype.COMMA) {
					next();
				} else if (peek().type == Tokentype.CLOSE_BRACKET) {
					next();
					break;
				} else {
					vec.addChild (parseExpr());
				}
			}
			return vec;
		} else {
			error ("Expecting open bracket to start vector, found " + peek());
		}
		return null;
	}

	public Tree parseParamlist () throws ParseException{
		Tree res = new Tree(Treetype.PARAMLIST, peek().fm);
		if (peek().type == Tokentype.OPEN_PAREN) {
			next();
			while (true) {
				if (peek().type == Tokentype.COMMA) {
					next();
				} else if (peek().type == Tokentype.CLOSE_PAREN) {
					next();
					break;
				} else {
					res.addChild (parseParam());
				}
			}
		} else {
			error ("Expecting open parenthesis to start parameter list, found " + peek());
		}
		return res;
	}

	public Tree parseParam () throws ParseException{
		Tree res = new Tree(Treetype.PARAM, peek().fm);
		// data is the name; optionally has one child, the default value.
		if (peek().type == Tokentype.IDENT) {
			res.data = peek().val;
			if (next().type == Tokentype.ASSIGN) {
				next();
				res.addChild (parseExpr());
			}
		} else {
			error ("Expecting identifier, found " + peek());
		}
		return res;
	}

	public Tree parseCondition () throws ParseException{
		Tree res = new Tree (Treetype.CONDITION, peek().fm);
		res.nest_depth = nesting;
		res.addChild (parseExpr());
		System.out.println ("Condition is \n" + res);
		System.out.println ("peek() is " + peek());
		return res;
	}

	public Tree parseStatement () throws ParseException{
		System.out.println ("In parseStatement, the leading token is " + peek());
		if (peek().is ("module")) {
			return parseModule();
		} else if (peek().is("function")) {
			return parseFunction();
		} else if (peek().is("declare")) {
			Tree res = new Tree (Treetype.DECLARE, peek().fm);
			next();
			if (peek().type == Tokentype.IDENT) {
				res.data = peek().val;
				if (next().type == Tokentype.ASSIGN) {
					next();
					res.addChild (parseExpr());
					if (peek().type == Tokentype.SEMICOLON) {
						next();
					} else {
						nferror ("Missing semicolon");
					}
					return res;
				} else {
					error ("Expecting assignment after identifier in declaration", peek().fm);
				}
			} else {
				error ("Expecting identifier after keyword 'declare'", res.fm);
			}

		} else if (peek().is ("if")) {
			/* An if statement's children must all be CONDITION nodes. A CONDITION tree will have as its first 
			 * child the condition, and subsequent children are the body. Else branches get null first children. */
			Tree res = new Tree (Treetype.IF, peek().fm);
			nesting += 1;
			res.nest_depth = nesting;
			next();
			Tree cond = parseCondition();
			res.addChild (cond);
			if (peek().type == Tokentype.OPEN_BRACE) {
				next();
				while (peek().type != Tokentype.CLOSE_BRACE) {
					cond.addChild (parseStatement());
				}
				next();	// skip the close brace
				boolean hit_else = false;
				while (peek().is ("else")) {
					next();
					if (peek().is ("if")) {	// this is an ELSE IF branch
						next();
						cond = parseCondition();
						res.addChild (cond);
						if (peek().type == Tokentype.OPEN_BRACE) {
							next();
						} else {
							error ("Expecting '{' after else if condition, found " + peek());
						}
					} else if (peek().type == Tokentype.OPEN_BRACE) {	// this is the ELSE branch
						next();
						cond = new Tree (Treetype.CONDITION, peek().fm);
						cond.nest_depth = nesting;
						cond.addChild (new Tree (Treetype.NOP, peek().fm));
						res.addChild (cond);	
						hit_else = true;
					} else {
						error ("Expecting 'if' or '{' after 'else', found " + peek());
					}
					// now we add the statements
					while (peek().type != Tokentype.CLOSE_BRACE) {
						cond.addChild (parseStatement());
					}
					next();
					if (hit_else) break;
				}
				nesting -= 1;
				return res;
			} else {
				error ("Expecting { after if condition, found " + peek());
			}
		}

		if (peek().is ("for") || peek().is ("intersection_for")) {
			/*
			 * Child 0: Either a VECTOR tree (for enumerated values) or a RANGE tree (with two expression children, for start and end vals)
			 * 		This tree has the variable name as its data.
			 * Subsequent children: statements of the body
			*/
			int nesting_save = nesting;
			nesting += 1;
			Tree res = new Tree (peek().is("for") ? Treetype.FOR : Treetype.INTFOR, peek().fm);
			Tree innermost = res;
			res.nest_depth = nesting;
			next();
			if (peek().type == Tokentype.OPEN_PAREN) {
				next();
			} else {
				nferror ("Expecting open parenthesis after loop keyword, found " + peek());
			}
			while (peek().type == Tokentype.IDENT) {	// good
				String name = peek().val;
				if (next().type == Tokentype.ASSIGN) {
					Tree assign = new Tree (Treetype.ASSIGN, name, peek().fm);
					next();
					Tree loopval = parseExpr ();
					assign.addChild (loopval);
					innermost.addChild (assign);

					System.out.println ("After parsing loop index portion, the current position is " + i + " --> " + peek());
					if (peek().type == Tokentype.COMMA) {	// then we have the nested loop shorthand
						Tree prev_inner = innermost;
						nesting ++;
						innermost = new Tree (res.type, peek().fm);
						innermost.nest_depth = nesting;
						prev_inner.addChild (innermost);	// this is the body of prev_inner.
						next();	// skip over the comma
						continue;	// go back to the top of the while
					}
					// now we can proceed to get the body of the loop.
					if (peek().type == Tokentype.CLOSE_PAREN) {
						next();
					}
					if (peek().type == Tokentype.OPEN_BRACE) {
						next();
						while (peek().type != Tokentype.CLOSE_BRACE) {
							innermost.addChild (parseStatement());
							System.out.println ("Added the following tree as a child of the for loop: " + res);
						}
						next();
						break;
					} else {
						innermost.addChild (parseStatement());
						break;
					}
				} else {
					error ("Expecting assignment to loop variable");
				}
			}
			/* One option to make for loops more uniform is to replace the vector form with an equivalent range form:
			 *
			 * for (x = [a_0, a_1, ... a_n]) {		can be replaced with
			 *
			 * for (__TEMP = [0:n]) {
			 * 	assign (x = a[__TEMP]) {
			*/ 

			nesting = nesting_save;
			return res;
		}

		if (peek().type != Tokentype.IDENT && peek().type != Tokentype.OP) {
			error ("Statements cannot start with " + peek());
		}
		if (peek().type == Tokentype.IDENT) {
			if (next().type == Tokentype.ASSIGN) {	// NOT the ASSIGN block - just a regular variable assignment. 
				prev();
				Tree t = new Tree (Treetype.ASSIGN, peek().fm);
				t.data = peek().val;
				next();
				next();
				t.addChild (parseExpr());
				if (peek().type == Tokentype.SEMICOLON) {
					next();
				} else {
					nferror ("Missing semicolon");
				}
				return t;
			} else {
				prev();
			}
		}
		Tree dmode = null;
		if (peek().type == Tokentype.OP && Op.get(peek().val).isTreemod()) {	// for the things that change the display mode of a subtree
			dmode = new Tree (Treetype.DISPMODE, peek().fm);
			dmode.data = Op.get(peek().val);
			next();
		}
		Tree call = parseCall(Treetype.MCALL);	// these are always going to be MODULE calls
		nesting += 1;	// the nesting will have been reset at the end of parseCall; we need to keep it incremented
						// until all of our children have been created.
		if (dmode != null) call.addChild (dmode);
		System.err.println ("The token after the parseCall to module " + call.name() + " is " + peek());
		if (peek().type == Tokentype.SEMICOLON) {
			next();
			nesting -= 1;
			return call;
		} else {
			if (peek().type == Tokentype.OPEN_BRACE) {
				next();
				while (peek().type != Tokentype.CLOSE_BRACE) {
					System.err.println ("Getting child of module; starting at token #" + i + " --> " + peek());
					call.addChild (parseStatement());
				}
				next();
			} else {
				System.err.println ("Getting one child of module; starting at token #" + i + " --> " + peek());
				call.addChild (parseStatement());
			}
		}
		nesting -= 1;
		return call;
	}

	/* Expressions */

	public Tree parseExpr () throws ParseException{
		Tree ch = parseL6 ();
		Token t = peek();
		if (t.type == Tokentype.QMARK) {
			next();
			Tree cond = new Tree (Treetype.COND_OP, peek().fm);
			cond.addChild (ch);
			cond.addChild (parseExpr());
			if (peek().type == Tokentype.COLON) {
				next();
				cond.addChild (parseExpr());
			} else {
				error ("Expecting colon after first branch of conditional operator, found " + peek());
			}
			return cond;
		}
		return ch;
	}

	public Tree parseL6 () throws ParseException{	// or
		Tree ch = parseL5 ();
		while (peek().isOp (Op.OR)) {
			System.err.println ("Detected or; token = " + i + ", tree = " + ch);
			Tree or = new Tree (Treetype.OP, Op.OR, peek().fm);
			next();
			or.addChild (ch);
			or.addChild (parseL5());
			ch = or;
		}
		return ch;
	}

	public Tree parseL5 () throws ParseException{	// and
		Tree ch = parseL4 ();
		while (peek().isOp (Op.AND)) {
			Tree and = new Tree (Treetype.OP, peek().getOp(), peek().fm);
			next ();
			and.addChild (ch);
			and.addChild (parseL4());
			ch = and;
		}
		return ch;
	}

	public Tree parseL4 () throws ParseException{	// relationals
		Tree lhs = parseL3 ();
		if (peek().getOp().isRelational()) {
			Tree rel = new Tree (Treetype.OP, peek().getOp(), peek().fm);
			next();
			rel.addChild (lhs);
			rel.addChild (parseL3());
			return rel;
		} else {
			return lhs;
		}
	}

	public Tree parseL3 () throws ParseException{	// add and subtract
		Tree ch = parseL2 ();
		while (peek().isOp ("+") || peek().isOp ("-")) {
			Tree parent = new Tree (Treetype.OP, peek().getOp(), peek().fm);
			next();
			parent.addChild (ch);
			parent.addChild (parseL2());
			ch = parent;
		}
		return ch;
	}

	public Tree parseL2 () throws ParseException{	// multiply, divide, mod
		Tree ch = parseL1 ();
		while (peek().isOp ("*") || peek().isOp ("/") || peek().isOp ("%")) {
			Tree parent = new Tree (Treetype.OP, peek().getOp(), peek().fm);
			next();
			parent.addChild (ch);
			parent.addChild (parseL1());
			ch = parent;
		}
		return ch;
	}

	public Tree parseL1 () throws ParseException{	// unaries 
		Tree root;
		if (peek().isOp("!") || peek().isOp("-") || peek().isOp("+")) {
			root = new Tree (Treetype.OP, peek().getOp(), peek().fm);
			next();
		} else {
			return parseL0();
		}
		Tree parent = root;
		while (peek().isOp ("!") || peek().isOp("-") || peek().isOp("+")) {
			Tree res = new Tree (Treetype.OP, peek().getOp(), peek().fm);
			next();
			parent.addChild (res);
			parent = res;
		}
		parent.addChild (parseL0());
		return root;
	}


	public Tree parseL0 () throws ParseException{	// and now the base-ish case.
		if (peek().type == Tokentype.OPEN_PAREN) {
			next();
			Tree res = parseExpr ();	// and back up we go!
			if (peek().type == Tokentype.CLOSE_PAREN) {
				next();
			} else {
				nferror ("Expecting close parenthesis, found " + peek());
			}
			return res;
		} else if (peek().type == Tokentype.OPEN_BRACKET) {	// vector or range
			return parseVectorOrRange();
		} else if (peek().type == Tokentype.BLIT) {	// boolean literal
			Tree res = new Tree (Treetype.BLIT, peek().fm);
			res.data = Boolean.parseBoolean (peek().val);
			next();
			return res;
		} else if (peek().type == Tokentype.SLIT) {	// string literal
			Tree res = new Tree(Treetype.SLIT, peek().fm);
			res.data = peek().val;
			next();
			return res;
		} else if (peek().type == Tokentype.FLIT) {	// float literal
			Tree res = new Tree (Treetype.FLIT, peek().fm);
			res.data = peek().nval;
			next();
			return res;
		} else if (peek().type == Tokentype.IDENT) {
			// now it could either be a function call or a variable reference (possibly with vector indexing).
			// We must look at the following token: if OPEN_PAREN, then it's a function call.
			Token n = next();
			if (n.type == Tokentype.OPEN_PAREN) {
				prev();
				return parseCall (Treetype.FCALL);
			} else {
				Tree top;
				Tree id = new Tree (Treetype.IDENT, peek().fm);
				top = id;
				id.data = prev().val;
				next();
				while (peek().type == Tokentype.OPEN_BRACKET) {
					next();
					Tree ptop = top;
					top = new Tree (Treetype.OP, Op.INDEX, peek().fm);
					top.addChild (ptop);
					top.addChild (parseExpr());
					if (peek().type != Tokentype.CLOSE_BRACKET) {
						error ("Expecting close bracket on vector index, found " + peek());
					}
					next();
				}
				return top;
			}
		} else {
			error ("Invalid token " + peek() + " in parseL0");
		}
		return null;
	}
}
