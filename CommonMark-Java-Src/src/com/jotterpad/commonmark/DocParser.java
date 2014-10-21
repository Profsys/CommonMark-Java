package com.jotterpad.commonmark;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.jotterpad.commonmark.library.CollectionUtils;
import com.jotterpad.commonmark.object.Block;
import com.jotterpad.commonmark.object.ListData;
import com.jotterpad.commonmark.object.OrderedListData;
import com.jotterpad.commonmark.object.RefMapItem;
import com.jotterpad.commonmark.object.UnorderedListData;
import com.jotterpad.commonmark.pattern.RegexPattern;

public class DocParser {

	private Block _doc, _tip;
	private String _subject;
	private int _pos;
	private HashMap<String, RefMapItem> _refMap;
	private InlineParser _inlineParser;

	public DocParser(String subject, int pos) {
		_doc = Block.makeBlock("Document", 1, 1);
		_subject = subject;
		_pos = pos;
		_tip = Block.makeBlock("Document", 1, 1);
		_refMap = new HashMap<String, RefMapItem>();
		_inlineParser = new InlineParser();
	}

	private boolean acceptsLine(String blockType) {
		return blockType.equals("Paragraph")
				|| blockType.equals("IndentedCode")
				|| blockType.equals("FencedCode");
	}

	private boolean endsWithBlankLine(Block block) {
		if (block.isLastLineBlank()) {
			return true;
		}
		if ((block.getTag().equals("List") || block.getTag().equals("ListItem"))
				|| block.getChildren().size() > 0) {
			return endsWithBlankLine(block.getChildren().get(
					block.getChildren().size() - 1));
		} else {
			return false;
		}
	}

	private void breakOutOfLists(Block block, int lineNumber) {
		Block b = block, lastList = null;
		while (true) {
			if (b.getTag().equals("List")) {
				lastList = b;
			}
			b = b.getParent();
			if (b == null) {
				break;
			}
		}

		if (lastList != null) {
			while (block != lastList) {
				finalize(block, lineNumber);
				block = block.getParent();
			}

			finalize(lastList, lineNumber);
			_tip = lastList.getParent();
		}
	}

	private void addLine(String line, int offset) {
		String s = line.substring(offset, line.length());
		if (!_tip.isOpen()) {
			throw new RuntimeException("Attempted to add line (" + line
					+ ") to closed container.");
		}
		_tip.getStrings().add(s);
	}

	private Block addChild(String tag, int lineNumber, int offset) {
		while (!(_tip.getTag() == "Document" || _tip.getTag() == "BlockQuote"
				|| _tip.getTag() == "ListItem" || (_tip.getTag() == "List" && tag == "ListItem"))) {
			finalize(_tip, lineNumber);
		}
		int columnNumber = offset + 1;
		Block newBlock = Block.makeBlock(tag, lineNumber, columnNumber);
		_tip.getChildren().add(newBlock);
		newBlock.setPrent(_tip);
		_tip = newBlock;
		return newBlock;
	}

	private boolean listsMatch(ListData listData, ListData itemData) {
		// TODO: NEED HELP!
		return listData.equals(itemData)
				&& listData.getClass().equals(itemData.getClass());
	}

	private ListData parseListMarker(String line, int offset) {
		String rest = line.substring(offset, line.length());
		ListData listData;
		if (RegexPattern.getInstance().getHRule().matcher(rest).matches()) {
			return null;
		}
		Matcher match1 = Pattern.compile("^[*+-]( +|$)").matcher(rest);
		Matcher match2 = Pattern.compile("^(\\d+)([.)])( +|$)").matcher(rest);

		int spacesAfterMarker = -1;
		boolean blankItem = false;

		if (match1.find()) {
			spacesAfterMarker = match1.group(1).length();
			listData = new UnorderedListData("Bullet", String.valueOf(match1
					.group(0).charAt(0)));
			blankItem = match1.group(0).length() == rest.length();
		} else if (match2.find()) {
			spacesAfterMarker = match2.group(3).length();
			listData = new OrderedListData("Ordered", Integer.valueOf(match2
					.group(1)), match2.group(2));
			blankItem = match2.group(0).length() == rest.length();
		} else {
			return null;
		}

		if (spacesAfterMarker >= 5 || spacesAfterMarker < 1 || blankItem) {
			if (match1.find()) {
				listData.setPadding(match1.group(0).length()
						- spacesAfterMarker + 1);
			} else if (match2.find()) {
				listData.setPadding(match2.group(0).length()
						- spacesAfterMarker + 1);
			}
		} else {
			if (match1.find()) {
				listData.setPadding(match1.group(0).length());
			} else if (match2.find()) {
				listData.setPadding(match2.group(0).length());
			}
		}

		return listData;
	}

	private void incorporateLine(String line, int lineNumber) {
		boolean allMatched = true, alreadyDone = false;
		int offset = 0;
		int CODE_INDENT = 4;
		boolean blank = false;
		int firstNonSpace;

		Block container = _doc;
		Block oldTip = _tip;

		line = RegexPattern.getInstance().deTabLine(line);
		while (container.getChildren().size() > 0) {
			ArrayList<Block> children = container.getChildren();
			Block lastChild = children.get(children.size() - 1);
			if (!lastChild.isOpen()) {
				break;
			}
			container = lastChild;

			// Regex Literal String
			int match = RegexPattern.getInstance().matchAt(
					Pattern.compile("[^ ]"), line, offset);
			if (match == -1) {
				firstNonSpace = line.length();
				blank = true;
			} else {
				firstNonSpace = match;
				blank = false;
			}
			int indent = firstNonSpace - offset;
			if (container.getTag().equals("BlockQuote")) {
				boolean matched = false;
				if (line.length() > firstNonSpace && line.length() > 0) {
					matched = line.charAt(firstNonSpace) == '>';
				}
				matched = matched && indent <= 3;
				if (matched) {
					offset = firstNonSpace + 1;
					// Prevent IndexError
					if (offset < line.length() && line.charAt(offset) == ' ') {
						offset++;
					}
				} else {
					allMatched = false;
				}
			} else if (container.getTag().equals("ListItem")) {
				if (indent >= container.getListData().getMarkerOffset()
						+ container.getListData().getPadding()) {
					offset += container.getListData().getMarkerOffset()
							+ container.getListData().getPadding();
				} else if (blank) {
					offset = firstNonSpace;
				} else {
					allMatched = false;
				}
			} else if (container.getTag().equals("IndentedCode")) {
				if (indent >= CODE_INDENT) {
					offset += CODE_INDENT;
				} else if (blank) {
					offset = firstNonSpace;
				} else {
					allMatched = false;
				}
			} else if (container.getTag().equals("ATXHeader")
					|| container.getTag().equals("SetextHeader")
					|| container.getTag().equals("HorizontalRule")) {
				allMatched = false;
			} else if (container.getTag().equals("FencedCode")) {
				int i = container.getFenceOffset();
				while (i > 0 && line.length() > offset
						&& line.charAt(offset) == ' ') {
					offset++;
					i--;
				}
			} else if (container.getTag().equals("HtmlBlock")) {
				if (blank) {
					allMatched = false;
				}
			} else if (container.getTag().equals("Paragraph")) {
				if (blank) {
					container.setLastLineBlank(true);
					allMatched = false;
				}
			}

			if (!allMatched) {
				container = container.getParent();
				break;
			}
		}

		Block lastMatchedContainer = container;

		if (blank && container.isLastLineBlank()) {
			breakOutOfLists(container, lineNumber);
		}

		while (!container.getTag().equals("FencedCode")
				&& !container.getTag().equals("IndentedCode")
				&& !container.getTag().equals("HtmlBlock")
				&& RegexPattern.getInstance().matchAt(
						Pattern.compile("^[ #`~*+_=<>0-9-]"), line, offset) != -1) {
			int match = RegexPattern.getInstance().matchAt(
					Pattern.compile("[^ ]"), line, offset);
			if (match == -1) {
				firstNonSpace = line.length();
				blank = true;
			} else {
				firstNonSpace = match;
				blank = false;
			}

			Matcher ATXmatch = Pattern.compile("^#{1,6}(?: +|$)").matcher(
					line.substring(firstNonSpace, line.length()));
			Matcher FENmatch = Pattern.compile("^`{3,}(?!.*`)|^~{3,}(?!.*~)")
					.matcher(line.substring(firstNonSpace, line.length()));
			Matcher PARmatch = Pattern.compile("^(?:=+|-+) *$").matcher(
					line.substring(firstNonSpace, line.length()));
			ListData data = parseListMarker(line, firstNonSpace);

			int indent = firstNonSpace - offset;
			if (indent >= CODE_INDENT) {
				if (!_tip.getTag().equals("Paragraph") && !blank) {
					offset += CODE_INDENT;
					closeUnmatchedBlocks(alreadyDone, oldTip, lineNumber,
							lastMatchedContainer);
				} else {
					break;
				}
			} else if (line.length() > firstNonSpace
					&& line.charAt(firstNonSpace) == '>') {
				offset = firstNonSpace + 1;
				// Prevent IndexError
				if (offset < line.length() && line.charAt(offset) == ' ') {
					offset++;
				}
				closeUnmatchedBlocks(alreadyDone, oldTip, lineNumber,
						lastMatchedContainer);
			} else if (ATXmatch.find()) {
				offset = firstNonSpace + ATXmatch.group(0).length();
				closeUnmatchedBlocks(alreadyDone, oldTip, lineNumber,
						lastMatchedContainer);
				container = addChild("ATXHeader", lineNumber, firstNonSpace);
				container.setLevel(ATXmatch.group(0).trim().length());

				// Regex Literal String
				if (Pattern.compile("\\\\#")
						.matcher(line.substring(offset, line.length())).find()) {
					String tempLine = line.substring(offset, line.length())
							.replaceAll("(?:(\\\\#) *#*| *#+) *$", "$1");
					ArrayList<String> strings = new ArrayList<String>();
					strings.add(tempLine);
					container.setStrings(strings);
				} else {
					String tempLine = line.substring(offset, line.length())
							.replaceAll("(?:(\\\\#) *#*| *#+) *$", "");
					ArrayList<String> strings = new ArrayList<String>();
					strings.add(tempLine);
					container.setStrings(strings);
				}
				break;
			} else if (FENmatch.find()) {
				int fenceLength = FENmatch.group(0).length();
				closeUnmatchedBlocks(alreadyDone, oldTip, lineNumber,
						lastMatchedContainer);
				container = addChild("FencedCode", lineNumber, firstNonSpace);
				container.setFenceLength(fenceLength);
				container.setFenceChar(FENmatch.group(0).charAt(0));
				container.setFenceOffset(firstNonSpace - offset);
				offset = firstNonSpace + fenceLength;
				break;
			} else if (RegexPattern.getInstance().matchAt(
					RegexPattern.getInstance().getHtmlBlockOpen(), line,
					firstNonSpace) != -1) {
				closeUnmatchedBlocks(alreadyDone, oldTip, lineNumber,
						lastMatchedContainer);
				container = addChild("HtmlBlock", lineNumber, firstNonSpace);
			} else if (container.getTag().equals("Paragraph")
					&& container.getStrings().size() == 1 && PARmatch.find()) {
				closeUnmatchedBlocks(alreadyDone, oldTip, lineNumber,
						lastMatchedContainer);
				container.setTag("SetextHeader");
				container.setLevel(PARmatch.group(0).charAt(0) == '=' ? 1 : 2);
				offset = line.length();
			} else if (RegexPattern.getInstance().matchAt(
					RegexPattern.getInstance().getHRule(), line, firstNonSpace) != -1) {
				closeUnmatchedBlocks(alreadyDone, oldTip, lineNumber,
						lastMatchedContainer);
				container = addChild("HorizontalRule", lineNumber,
						firstNonSpace);
				offset = line.length() - 1;
				break;
			} else if (data != null) {
				closeUnmatchedBlocks(alreadyDone, oldTip, lineNumber,
						lastMatchedContainer);
				data.setMarkerOffset(indent);
				offset = firstNonSpace + data.getPadding();
				if (!container.getTag().equals("List")
						|| listsMatch(container.getListData(), data)) {
					container = addChild("List", lineNumber, firstNonSpace);
					container.setListData(data);
				}
				container = addChild("ListItem", lineNumber, firstNonSpace);
				container.setListData(data);
			} else {
				break;
			}

			if (acceptsLine(container.getTag())) {
				break;
			}
		}

		int match = RegexPattern.getInstance().matchAt(Pattern.compile("[^ ]"),
				line, offset);
		if (match == -1) {
			firstNonSpace = line.length();
			blank = true;
		} else {
			firstNonSpace = match;
			blank = false;
		}
		int indent = firstNonSpace - offset;

		if (!_tip.equals(lastMatchedContainer) && !blank
				&& _tip.getTag().equals("Paragraph")
				&& _tip.getStrings().size() > 0) {

			// ERROR: Line 454 JS, 1071 PY
			// _lastLineBlank = false;
			// Believe to be like this:
			container.setLastLineBlank(false);
			// ///

			addLine(line, offset);
		} else {
			closeUnmatchedBlocks(alreadyDone, oldTip, lineNumber,
					lastMatchedContainer);
			boolean isLastLineBlank = blank
					&& (container.getTag().equals("BlockQuote")
							|| container.getTag().equals("FencedCode") || (container
							.getTag().equals("ListItem")
							&& container.getChildren().size() == 0 && container
							.getStartLine() == lineNumber));
			container.setLastLineBlank(isLastLineBlank);
			Block cont = container;
			while (cont.getParent() != null) {
				cont.getParent().setLastLineBlank(false);
				cont = cont.getParent();
			}

			if (container.getTag().equals("IndentedCode")
					|| container.getTag().equals("HtmlBlock")) {
				addLine(line, offset);
			} else if (container.getTag().equals("FencedCode")) {
				boolean isMatched = false;
				if (line.length() > 0) {
					// Regex Literal String
					isMatched = line.length() > firstNonSpace
							&& line.charAt(firstNonSpace) == container
									.getFenceChar()
							&& Pattern.matches("^(?:`{3,}|~{3,})(?= *$)",
									line.substring(firstNonSpace));
				}
				isMatched = indent <= 3 && isMatched;
				// Regex Literal String
				Matcher FENmatch = Pattern.compile("^(?:`{3,}|~{3,})(?= *$)")
						.matcher(line.substring(firstNonSpace));
				if (isMatched
						&& FENmatch.group(0).length() >= container
								.getFenceLength()) {
					finalize(container, lineNumber);
				} else {
					addLine(line, offset);
				}
			} else if (container.getTag().equals("ATXHeader")
					|| container.getTag().equals("SetextHeader")
					|| container.getTag().equals("HorizontalRule")) {

			} else {
				if (acceptsLine(container.getTag())) {
					addLine(line, firstNonSpace);
				} else if (blank) {

				} else if (!container.getTag().equals("HorizontalRule")
						&& !container.getTag().equals("SetextHeader")) {
					container = addChild("Paragraph", lineNumber, firstNonSpace);
				} else {

				}
			}
		}
	}

	private void closeUnmatchedBlocks(Boolean alreadyDone, Block oldTip,
			int lineNumber, Block lastMatchedContainer) {
		finalize(oldTip, lineNumber);
		oldTip = oldTip.getParent();
		alreadyDone = true;
	}

	// STUB
	private void finalize(Block block, int lineNumber) {
		if (!block.isOpen()) {
			return;
		}

		block.setIsOpen(false);
		if (lineNumber > block.getStartLine()) {
			block.setEndLine(lineNumber - 1);
		} else {
			block.setEndLine(lineNumber);
		}

		if (block.getTag().equals("Paragraph")) {
			// ...
			block.setStringContent("");
			ArrayList<String> lines = block.getStrings();
			for (int i = 0; i < lines.size(); i++) {
				String line = lines.get(i);
				lines.set(i, Pattern.compile("^  *", Pattern.MULTILINE)
						.matcher(line).replaceAll(""));
			}
			block.setStringContent(CollectionUtils.join("\n", block.getStrings()));
			// LINE 1133
		} else if (block.getTag().equals("ATXHeader")
				|| block.getTag().equals("SetextHeader")
				|| block.getTag().equals("HorizontalRule")) {
			// ...
		} else if (block.getTag().equals("IndentedCode")) {
			// ...
		} else if (block.getTag().equals("FencedCode")) {
			// ...
		} else if (block.getTag().equals("List")) {
			// ...
		} else {

		}

		_tip = block.getParent();
	}

	private void processInlines(Block block) {
		if (block.getTag().equals("ATXHeader")
				|| block.getTag().equals("SetextHeader")
				|| block.getTag().equals("HorizontalRule")) {
			block.setInlineContent(_inlineParser.parse(block.getStringContent()
					.trim(), _refMap));
			block.setStringContent("");
		}

		if (block.getChildren() != null && block.getChildren().size() > 0) {
			for (Block child : block.getChildren()) {
				processInlines(child);
			}
		}
	}

	private Block parse(String input) {
		_doc = Block.makeBlock("Document", 1, 1);
		_tip = _doc;
		_refMap = new HashMap<String, RefMapItem>();

		// Regex Literal String
		String tempInput = input.replaceAll("\\n$", "");
		String[] lines = Pattern.compile("\\r\\n|\\n|\\r").split(tempInput);
		for (int i = 0; i < lines.length; i++) {
			incorporateLine(lines[i], i + 1);
		}

		while (_tip != null) {
			finalize(_tip, lines.length - 1);
		}
		processInlines(_doc);

		return _doc;
	}
}
