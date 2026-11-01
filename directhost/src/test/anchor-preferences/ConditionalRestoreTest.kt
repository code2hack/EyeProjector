package com.code2hack.eyeprojector.directhost.anchor.preferences

import com.code2hack.eyeprojector.directhost.anchor.target.AnchorTargetDescriptor
import java.io.IOException
import java.util.concurrent.*
import org.junit.Assert.*
import org.junit.Test

class ConditionalRestoreTest {
    private val pkg="com.code2hack.eyeprojector.hostprobe"
    private fun anchor(mode:AnchorMode=AnchorMode.HOLD,resource:String="$pkg:id/hold",packageName:String=pkg)=
        AppAnchor(AnchorTargetDescriptor(packageName,"$packageName/.Fixture",1,resource,"Button"),mode)
    private class Storage:AppAnchorStorage {
        val values=mutableMapOf<String,AppAnchor>();var writes=0;var failure:IOException?=null
        override fun read(packageName:String)=values[packageName]
        override fun write(packageName:String,anchor:AppAnchor?){failure?.let{throw it};writes++;if(anchor==null)values.remove(packageName)else values[packageName]=anchor}
    }
    @Test fun matchingCompleteReplaceClearAndAbsentNoopRetainExistingRevisionSemantics() {
        val disk=Storage();val store=AppAnchorStore(disk);val changes=mutableListOf<AppAnchorChange>()
        store.observe(pkg,changes::add)
        val absent=store.snapshot(pkg)
        assertEquals(absent.context,store.compareAndSet(absent,null));assertEquals(0,disk.writes);assertTrue(changes.isEmpty())
        val hold=anchor();val first=store.compareAndSet(absent,hold)!!
        assertEquals(absent.context.revision+1,first.revision);assertEquals(hold,store.read(pkg))
        val click=hold.copy(mode=AnchorMode.CLICK)
        val second=store.compareAndSet(store.snapshot(pkg),click)!!
        assertEquals(first.revision+1,second.revision);assertEquals(click,store.read(pkg))
        val third=store.compareAndSet(store.snapshot(pkg),null)!!
        assertEquals(second.revision+1,third.revision);assertNull(store.read(pkg));assertEquals(3,disk.writes)
        assertEquals(listOf(AppAnchorChange.Replaced(null,hold,first),AppAnchorChange.Replaced(hold,click,second),AppAnchorChange.Cleared(click,third)),changes)
    }
    @Test fun staleInstanceRevisionPackageAndExactValueNeverWriteAdvanceOrNotify() {
        val disk=Storage();val store=AppAnchorStore(disk);val value=anchor();store.replace(value)
        val expected=store.snapshot(pkg);val events=mutableListOf<AppAnchorChange>();store.observe(pkg,events::add)
        val invalid=listOf(expected.copy(context=expected.context.copy(instanceId="another-instance")),
            expected.copy(context=expected.context.copy(revision=expected.context.revision-1)),
            expected.copy(context=expected.context.copy(packageName="another.package")),
            expected.copy(anchor=value.copy(mode=AnchorMode.CLICK)),
            expected.copy(anchor=anchor(resource="$pkg:id/independent")),expected.copy(anchor=null))
        val before=disk.writes
        invalid.forEach{assertNull(store.compareAndSet(it,null));assertEquals(expected,store.snapshot(pkg))}
        assertNull(store.compareAndSet(expected,anchor(packageName="another.package")))
        assertEquals(before,disk.writes);assertTrue(events.isEmpty())
        // Same revision with an independently changed complete storage value must also fail.
        val other=anchor(resource="$pkg:id/outside-store");disk.values[pkg]=other
        assertNull(store.compareAndSet(expected,null));assertEquals(other,store.read(pkg));assertTrue(store.isCurrent(expected.context))
        assertEquals(before,disk.writes);assertTrue(events.isEmpty())
    }
    @Test fun independentReplacementBetweenSnapshotAndConditionalCommitIsPreserved() {
        val disk=Storage();val store=AppAnchorStore(disk);store.replace(anchor())
        val captured=store.snapshot(pkg);val independent=anchor(resource="$pkg:id/independent")
        val arrived=CountDownLatch(1);val resume=CountDownLatch(1);val pool=Executors.newSingleThreadExecutor()
        try {
            val pending=pool.submit<AnchorSettingContext?>{arrived.countDown();check(resume.await(2,TimeUnit.SECONDS));store.compareAndSet(captured,null)}
            check(arrived.await(2,TimeUnit.SECONDS));store.replace(independent);val current=store.snapshot(pkg);val writes=disk.writes
            resume.countDown();assertNull(pending.get(2,TimeUnit.SECONDS));assertEquals(current,store.snapshot(pkg));assertEquals(writes,disk.writes)
        }finally{resume.countDown();pool.shutdownNow()}
    }
    @Test fun failedConditionalWriteKeepsOriginalContextValueAndEventsForRetry() {
        val disk=Storage();val store=AppAnchorStore(disk);store.replace(anchor());val expected=store.snapshot(pkg)
        val events=mutableListOf<AppAnchorChange>();store.observe(pkg,events::add)
        val original=IOException("complete atomic storage failure");disk.failure=original
        try{store.compareAndSet(expected,null);fail("write should throw")}catch(error:IOException){assertSame(original,error)}
        assertEquals(expected,store.snapshot(pkg));assertTrue(events.isEmpty())
        disk.failure=null;assertNotNull(store.compareAndSet(expected,null));assertEquals(1,events.size)
    }
    @Test fun otherPackageMutationDoesNotRejectOriginalFixtureAndCannotBeOverwritten() {
        val disk=Storage();val store=AppAnchorStore(disk);store.replace(anchor());val expected=store.snapshot(pkg)
        val other=anchor(packageName="example.independent");store.replace(other);val otherSetting=store.snapshot(other.packageName)
        assertNotNull(store.compareAndSet(expected,null));assertEquals(otherSetting,store.snapshot(other.packageName));assertNull(store.read(pkg))
    }
    @Test fun conditionalObserversRunOutsideStateAndNestedCommitsStayFifo() {
        val disk=Storage();val failures=mutableListOf<RuntimeException>();val store=AppAnchorStore(disk,failures::add)
        val prior=anchor(AnchorMode.CLICK);store.replace(prior)
        val expected=store.snapshot(pkg);val fixture=anchor();val order=mutableListOf<String>()
        val pool=Executors.newSingleThreadExecutor()
        try {
            store.observe(pkg){change->
                order.add("first:${change.context.revision}")
                val read=pool.submit<AppAnchorSetting>{store.snapshot(pkg)}.get(2,TimeUnit.SECONDS)
                assertEquals(change.context,read.context)
                if(change is AppAnchorChange.Replaced)assertNotNull(store.compareAndSet(read,null))
            }
            store.observe(pkg){order.add("second:${it.context.revision}")}
            val committed=store.compareAndSet(expected,fixture)!!
            assertEquals(listOf("first:${committed.revision}","second:${committed.revision}","first:${committed.revision+1}","second:${committed.revision+1}"),order)
            assertTrue(failures.isEmpty());assertNull(store.read(pkg));assertEquals(committed.revision+1,store.snapshot(pkg).context.revision)
        }finally{pool.shutdownNow()}
    }
}
