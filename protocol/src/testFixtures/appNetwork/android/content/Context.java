package android.content;
public class Context {
 public static final String CONNECTIVITY_SERVICE="connectivity";
 public Object service;
 public RuntimeException failure;
 public Object getSystemService(String name) { if(failure!=null)throw failure; if(!CONNECTIVITY_SERVICE.equals(name))throw new AssertionError("Wrong service");return service; }
}
