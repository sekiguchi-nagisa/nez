package nez;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.RandomAccessFile;
import java.io.UnsupportedEncodingException;
import java.util.LinkedHashMap;
import java.util.Map;

import nez.util.StringUtils;
import nez.vm.Context;

public abstract class SourceContext extends Context {
//	public final static int TextEOF   = 0;
//	public final static int BinaryEOF = 256; 
	
	private String     fileName;
	protected long     startLineNum = 1;

	protected SourceContext(String fileName, long linenum) {
		this.fileName = fileName;
		this.startLineNum = linenum;
	}
	
	@Override
	public abstract int     byteAt(long pos);
	@Override
	public abstract long    length();

	@Override
	public abstract boolean match(long pos, byte[] text);
	@Override
	public abstract String  substring(long startIndex, long endIndex);
	@Override
	public abstract long    linenum(long pos);

	/* handling input stream */
	
	@Override
	public final String getResourceName() {
		return fileName;
	}

	final String getFilePath(String fileName) {
		int loc = this.getResourceName().lastIndexOf("/");
		if(loc > 0) {
			return this.getResourceName().substring(0, loc+1) + fileName; 
		}
		return fileName;
	}

	public final int charAt(long pos) {
		int c = byteAt(pos), c2, c3, c4;
		int len = StringUtils.lengthOfUtf8(c);
		switch(len) {
		case 1:
			return c;
		case 2:
			// 0b11111 = 31
			// 0b111111 = 63
			c2 = byteAt(pos + 1) & 63;
			return ((c & 31) << 6) | c2;
		case 3:
			c2 = byteAt(pos + 1) & 63;
			c3 = byteAt(pos + 2) & 63;
			return ((c & 15) << 12) | c2 << 6 | c3;
		case 4:
			c2 = byteAt(pos + 1) & 63;
			c3 = byteAt(pos + 2) & 63;
			c4 = byteAt(pos + 3) & 63;
			return ((c & 7) << 18) | c2 << 12 | c3 << 6 | c4;
		}
		return -1;
	}

	public final int charLength(long pos) {
		int c = byteAt(pos);
		return StringUtils.lengthOfUtf8(c);
	}

	private final long getLineStartPosition(long fromPostion) {
		long startIndex = fromPostion;
		if(!(startIndex < this.length())) {
			startIndex = this.length() - 1;
		}
		if(startIndex < 0) {
			startIndex = 0;
		}
		while(startIndex > 0) {
			int ch = byteAt(startIndex);
			if(ch == '\n') {
				startIndex = startIndex + 1;
				break;
			}
			startIndex = startIndex - 1;
		}
		return startIndex;
	}

	public final String getIndentText(long fromPosition) {
		long startPosition = this.getLineStartPosition(fromPosition);
		long i = startPosition;
		String indent = "";
		for(; i < fromPosition; i++) {
			int ch = this.byteAt(i);
			if(ch != ' ' && ch != '\t') {
				if(i + 1 != fromPosition) {
					for(long j = i;j < fromPosition; j++) {
						indent = indent + " ";
					}
				}
				break;
			}
		}
		indent = this.substring(startPosition, i) + indent;
		return indent;
	}

	public final String formatPositionMessage(String messageType, long pos, String message) {
		return "(" + this.getResourceName() + ":" + this.linenum(pos) + ") [" + messageType +"] " + message;
	}

	@Override
	public final String formatPositionLine(String messageType, long pos, String message) {
		return this.formatPositionMessage(messageType, pos, message) + this.getTextAround(pos, "\n ");
	}

	private final String getTextAround(long pos, String delim) {
		int ch = 0;
		if(pos < 0) {
			pos = 0;
		}
		while(this.byteAt(pos) == this.EOF() && pos > 0) {
			pos -= 1;
		}
		long startIndex = pos;
		while(startIndex > 0) {
			ch = byteAt(startIndex);
			if(ch == '\n' && pos - startIndex > 0) {
				startIndex = startIndex + 1;
				break;
			}
			if(pos - startIndex > 60 && ch < 128) {
				break;
			}
			startIndex = startIndex - 1;
		}
		long endIndex = pos + 1;
		if(endIndex < this.length()) {
			while((ch = byteAt(endIndex)) != this.EOF()) {
				if(ch == '\n' || endIndex - startIndex > 78 && ch < 128) {
					break;
				}
				endIndex = endIndex + 1;
			}
		}
		else {
			endIndex = this.length();
		}
		StringBuilder source = new StringBuilder();
		StringBuilder marker = new StringBuilder();
		for(long i = startIndex; i < endIndex; i++) {
			ch = byteAt(i);
			if(ch == '\n') {
				source.append("\\N");
				if(i == pos) {
					marker.append("^^");
				}
				else {
					marker.append("\\N");
				}
			}
			else if(ch == '\t') {
				source.append("    ");
				if(i == pos) {
					marker.append("^^^^");
				}
				else {
					marker.append("    ");
				}
			}
			else {
				source.append((char)ch);
				if(i == pos) {
					marker.append("^");
				}
				else {
					marker.append(" ");
				}
			}
		}
		return delim + source.toString() + delim + marker.toString();
	}
	
	public final static SourceContext newStringSourceContext(String str) {
		return new StringSourceContext(str);
	}

	public final static SourceContext newStringSourceContext(String resource, long linenum, String str) {
		return new StringSourceContext(resource, linenum, str);
	}

	public final static SourceContext loadSource(String fileName) throws IOException {
		InputStream Stream = SourceContext.class.getResourceAsStream("/" + fileName);
		if (Stream == null) {
			File f = new File(fileName);
			if(f.length() > 16 * 1024) {
				return new FileSourceContext(fileName);
			}
			Stream = new FileInputStream(fileName);
		}
		BufferedReader reader = new BufferedReader(new InputStreamReader(Stream));
		StringBuilder builder = new StringBuilder();
		String line = reader.readLine();
		while(line != null) {
			builder.append(line);
			builder.append("\n");
			line = reader.readLine();
		}
		reader.close();
		return new StringSourceContext(fileName, 1, builder.toString());
	}



	
	
}

class StringSourceContext extends SourceContext {
	private byte[] utf8;
	long textLength;

	StringSourceContext(String sourceText) {
		super("(string)", 1);
		this.utf8 = toZeroTerminalByteSequence(sourceText);
		this.textLength = utf8.length-1;
	}

	StringSourceContext(String resource, long linenum, String sourceText) {
		super(resource, linenum);
		this.utf8 = toZeroTerminalByteSequence(sourceText);
		this.textLength = utf8.length-1;
	}

	private final byte[] toZeroTerminalByteSequence(String s) {
		byte[] b = StringUtils.toUtf8(s);
		byte[] b2 = new byte[b.length+1];
		System.arraycopy(b, 0, b2, 0, b.length);
		return b2;
	}

	@Override
	public final long length() {
		return this.textLength;
	}

	@Override
	public final int byteAt(long pos) {
		return this.utf8[(int)pos] & 0xff;
	}
	
	@Override
	public final int EOF() {
		return 0;
	}

	@Override
	public final boolean match(long pos, byte[] text) {
		if(pos + text.length > this.textLength) {
			return false;
		}
		for(int i = 0; i < text.length; i++) {
			if(text[i] != this.utf8[(int)pos + i]) {
				return false;
			}
		}
		return true;
	}

	@Override
	public final String substring(long startIndex, long endIndex) {
		try {
			return new String(this.utf8, (int)(startIndex), (int)(endIndex - startIndex), StringUtils.DefaultEncoding);
		} catch (UnsupportedEncodingException e) {
		}
		return null;
	}

	@Override
	public final byte[] subbyte(long startIndex, long endIndex) {
		byte[] b = new byte[(int)(endIndex - startIndex)];
		System.arraycopy(this.utf8, (int)(startIndex), b, 0, b.length);
		return b;
	}

	@Override
	public final long linenum(long pos) {
		long count = this.startLineNum;
		int end = (int)pos;
		if(end >= this.utf8.length) {
			end = this.utf8.length;
		}
		for(int i = 0; i < end; i++) {
			if(this.utf8[i] == '\n') {
				count++;
			}
		}
		return count;
	}
}

class FileSourceContext extends SourceContext {
	public final static int PageSize = 4096;

	private RandomAccessFile file;
	private long fileLength = 0;
	private long buffer_offset;
	private byte[] buffer;
	private long lines[];

	private final int FifoSize = 8; 
	private LinkedHashMap<Long, byte[]> fifoMap = null;

	FileSourceContext(String fileName) throws IOException {
		super(fileName, 1);
		this.file = new RandomAccessFile(fileName, "r");
		this.fileLength = this.file.length();
		this.buffer_offset = 0;
		lines = new long[((int)this.fileLength / PageSize) + 1];
		lines[0] = 1;
		if(this.FifoSize > 0) {
			this.fifoMap = new LinkedHashMap<Long, byte[]>(FifoSize) {  //FIFO
				private static final long serialVersionUID = 6725894996600788028L;
				@Override
				protected boolean removeEldestEntry(Map.Entry<Long, byte[]> eldest)  {
					if(this.size() > FifoSize) {
						return true;			
					}
					return false;
				}
			};
			this.buffer = null;
		}
		else {
			this.fifoMap = null;
			this.buffer = new byte[PageSize];
		}
		this.readMainBuffer(this.buffer_offset);

	}
	@Override
	public final long length() {
		return this.fileLength;
	}

	private long buffer_alignment(long pos) {
		return (pos / PageSize) * PageSize;
	}

	@Override
	public final int byteAt(long pos) {
		int buffer_pos = (int)(pos - this.buffer_offset);
		if(!(buffer_pos >= 0 && buffer_pos < PageSize)) {
			this.buffer_offset = buffer_alignment(pos);
			this.readMainBuffer(this.buffer_offset);
			buffer_pos = (int)(pos - this.buffer_offset);
		}
		return this.buffer[buffer_pos] & 0xff;
	}

	@Override
	public final int EOF() {
		return 0;  //
	}
	
	@Override
	public final boolean match(long pos, byte[] text) {
		int offset = (int)(pos - this.buffer_offset);
		if(offset >= 0 && offset + text.length <= PageSize) {
			switch(text.length) {
			case 0:
				break;
			case 1:
				if(text[0] == this.buffer[offset]) {
					return true;
				}
				return false;
			case 2:
				if(text[0] == this.buffer[offset] && text[1] == this.buffer[offset+1]) {
					return true;
				}
				return false;
			case 3:
				if(text[0] == this.buffer[offset] && text[1] == this.buffer[offset+1] && text[2] == this.buffer[offset+2]) {
					return true;
				}
				return false;
			case 4:
				if(text[0] == this.buffer[offset] && text[1] == this.buffer[offset+1] && text[2] == this.buffer[offset+2] && text[3] == this.buffer[offset+3]) {
					return true;
				}
				return false;
			default:
				for(int i = 0; i < text.length; i++) {
					if(text[i] != this.buffer[offset+i]) {
						return false;
					}
				}
			}
			return true;
		}
		for(int i = 0; i < text.length; i++) {
			if((text[i] & 0xff) != this.byteAt(pos + i)) {
				return false;
			}
		}
		return true;
	}

	@Override
	public final String substring(long startIndex, long endIndex) {
		if(endIndex > startIndex) {
			try {
				long off_s = buffer_alignment(startIndex);
				long off_e = buffer_alignment(endIndex);
				if(off_s == off_e) {
					if(this.buffer_offset != off_s) {
						this.buffer_offset = off_s;
						this.readMainBuffer(this.buffer_offset);
					}
					return new String(this.buffer, (int)(startIndex - this.buffer_offset), (int)(endIndex - startIndex), StringUtils.DefaultEncoding);
				}
				else {
					byte[] b = new byte[(int)(endIndex - startIndex)];
					this.readStringBuffer(startIndex, b);
					return new String(b, StringUtils.DefaultEncoding);
				}
			} catch (UnsupportedEncodingException e) {
				e.printStackTrace();
			}
		}
		return "";
	}
	
	@Override
	public final byte[] subbyte(long startIndex, long endIndex) {
		byte[] b = null;
		if(endIndex > startIndex) {
			long off_s = buffer_alignment(startIndex);
			long off_e = buffer_alignment(endIndex);
			b = new byte[(int)(endIndex - startIndex)];
			if(off_s == off_e) {
				if(this.buffer_offset != off_s) {
					this.buffer_offset = off_s;
					this.readMainBuffer(this.buffer_offset);
				}
				System.arraycopy(this.buffer, (int)(startIndex - this.buffer_offset), b, 0, b.length);
			}
			else {
				this.readStringBuffer(startIndex, b);
			}
		}
		return b;
	}


	private int lineIndex(long pos) {
		return (int)(pos / PageSize);
	}

	private long startLineNum(long pos) {
		int index = lineIndex(pos);
		return this.lines[index];
	}

	@Override
	public final long linenum(long pos) {
		long count = startLineNum(pos);
		byteAt(pos); // restore buffer at pos
		int offset = (int)(pos - this.buffer_offset);
		for(int i = 0; i < offset; i++) {
			if(this.buffer[i] == '\n') {
				count++;
			}
		}
		return count;
	}

	private void readMainBuffer(long pos) {
		int index = lineIndex(pos);
		if(this.lines[index] == 0) {
			long count = this.lines[index-1];
			for(int i = 0; i < this.buffer.length; i++) {
				if(this.buffer[i] == '\n') {
					count++;
				}
			}
			this.lines[index] = count;
		}
		if(this.fifoMap != null) {
			Long key = pos;
			byte[] buf = this.fifoMap.get(key);
			if(buf == null) {
				buf = new byte[PageSize];
				this.readBuffer(pos, buf);
				this.fifoMap.put(key, buf);
				this.buffer = buf;
			}
			else {
				this.buffer = buf;
			}
		}
		else {
			this.readBuffer(pos, this.buffer);
		}
	}

	private void readBuffer(long pos, byte[] b) {
		try {
			this.file.seek(pos);
			int readsize = this.file.read(b);
			for(int i = readsize; i < b.length; i++) {
				b[i] = 0;
			}
			//		if(this.stat != null) {
			//			stat.readFile(b.length);
			//		}
		} catch (IOException e) {
			e.printStackTrace();
		}
	}

	private void readStringBuffer(long pos, byte[] buf) {
		if(this.fifoMap != null) {
			int copied = 0;
			long start = pos;
			long end = pos + buf.length;
			while(start < end) {
				long offset = this.buffer_alignment(start);
				if(this.buffer_offset != offset) {
					this.buffer_offset = offset;
					this.readMainBuffer(offset);
				}
				int start_off = (int)(start - offset);
				int end_off = (int)(end - offset);				
				if(end_off <= PageSize) {
					int len = end_off - start_off;
					System.arraycopy(this.buffer, start_off, buf, copied, len);
					copied += len;
					assert(copied == buf.length);
					return;
				}
				else {
					int len = PageSize - start_off;
					System.arraycopy(this.buffer, start_off, buf, copied, len);
					copied += len;
					start += len;
				}
			}
		}
		else {
			this.readBuffer(pos, buf);
		}
	}

	//public final String substringDebug(long startIndex, long endIndex) {
	//	String s= this.substring(startIndex, endIndex);
	//	if(this.debug != null) {
	//		String s2= this.debug.substring(startIndex, endIndex);
	//		if(!s.equals(s2)) {
	//			System.out.println("s1: " + s);
	//			System.out.println("s2: " + s2);
	//			Main._Exit(1, "different " + this.fileName + " pos=" + startIndex + " end=" + endIndex);
	//		}
	//	}
	//	return s;
	//}
	//
	//public final int charAtDebug(long n) {
	//	//assert(n < this.fileLength);
	//	int c = this.charAt(n);
	//	if(this.debug != null) {
	//		int c2 = this.debug.charAt(n);
	//		if(c != c2) {
	//			Main._Exit(1, "different " + this.fileName + " pos=" + n + "c='"+c+"', c2='"+c2+"'");
	//		}
	//	}
	//	return c;
	//}

}


