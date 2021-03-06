package hudson.util

import com.trilead.ssh2.crypto.Base64
import hudson.FilePath
import jenkins.security.ConfidentialStoreRule
import org.junit.Rule
import org.junit.Test

import javax.crypto.Cipher

import static hudson.Util.createTempDir

/**
 *
 *
 * @author Kohsuke Kawaguchi
 */
class SecretRewriterTest {
    @Rule
    public MockSecretRule mockSecretRule = new MockSecretRule()

    @Rule
    public ConfidentialStoreRule confidentialStoreRule = new ConfidentialStoreRule();

    @Test
    void singleFileRewrite() {
        def o = encryptOld('foobar') // old
        def n = encryptNew('foobar') // new
        roundtrip "<foo>${o}</foo>",
                  "<foo>${n}</foo>"

        roundtrip "<foo>${o}</foo><foo>${o}</foo>",
                  "<foo>${n}</foo><foo>${n}</foo>"

        roundtrip "<foo>${n}</foo>",
                  "<foo>${n}</foo>"

        roundtrip "  <foo>thisIsLegalBase64AndLongEnoughThatItCouldLookLikeSecret</foo>  ",
                  "  <foo>thisIsLegalBase64AndLongEnoughThatItCouldLookLikeSecret</foo>  "

        // to be rewritten, it needs to be between a tag
        roundtrip "<foo>$o", "<foo>$o"
        roundtrip "$o</foo>", "$o</foo>"

        //
        roundtrip "<abc>\n<foo>$o</foo>\n</abc>", "<abc>\n<foo>$n</foo>\n</abc>"
    }

    void roundtrip(String before, String after) {
        def sr = new SecretRewriter(null);
        def f = File.createTempFile("test","xml");
        try {
            f.text = before
            sr.rewrite(f,null)
            assert after.trim().replaceAll("(\\r|\\n)", "") == f.text.trim().replaceAll("(\\r|\\n)", "")
        } finally {
            f.delete()
        }
    }

    String encryptOld(str) {
        def cipher = Secret.getCipher("AES");
        cipher.init(Cipher.ENCRYPT_MODE, Secret.legacyKey);
        return new String(Base64.encode(cipher.doFinal((str + Secret.MAGIC).getBytes("UTF-8"))))
    }

    String encryptNew(str) {
        return Secret.fromString(str).encryptedValue
    }

    /**
     * Directory rewrite and recursion detection
     */
    @Test
    void recursionDetection() {
        def backup = createTempDir()
        def sw = new SecretRewriter(backup);
        def st = StreamTaskListener.fromStdout()

        def o = encryptOld("Hello world")
        def n = encryptNew("Hello world")
        def payload = "<msg>$o</msg>"
        def answer = "<msg>$n</msg>"

        // set up some directories with stuff
        def t = createTempDir()
        def dirs = ["a", "b", "c", "c/d", "c/d/e"]
        dirs.each { p ->
            def d = new File(t, p)
            d.mkdir()
            new File(d,"foo.xml").text = payload
        }

        // stuff outside
        def t2 = createTempDir()
        new File(t2,"foo.xml").text = payload

        // some recursions as well as valid symlinks
        new FilePath(t).child("c/symlink").symlinkTo("..",st)
        new FilePath(t).child("b/symlink").symlinkTo(".",st)
        new FilePath(t).child("a/symlink").symlinkTo(t2.absolutePath,st)

        assert 6==sw.rewriteRecursive(t, st)

        dirs.each { p->
            assert new File(t,"$p/foo.xml").text.trim()==answer
            assert new File(backup,"$p/foo.xml").text.trim()==payload
        }

        // t2 is only reachable by following a symlink. this should be covered, too
        assert new File(t2,"foo.xml").text.trim()==answer.trim();
    }

}
