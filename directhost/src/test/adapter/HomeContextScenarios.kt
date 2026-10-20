package com.code2hack.eyeprojector.directhost
import android.content.Intent
import com.code2hack.eyeprojector.directhost.apps.ChosenApp
import java.util.concurrent.*

object HomeContextScenarios {
    fun run(host:HostBootstrap,drain:()->Unit,lifecycle:()->Unit,field:(String)->Any?,attach:(FakeService)->Unit) {
        host.start("home-a");drain()
        val service=FakeService().apply{state="STREAMING"};attach(service)
        PhoneSessionController.host=host
        val a=host.status().projectionContext
        fun intent(c:ProjectionContext)=Intent().putExtra(ProjectionContext.OWNER_EXTRA,c.ownerEpoch).putExtra(ProjectionContext.TOKEN_EXTRA,c.projectionToken)
        val activity=ProjectedHomeActivity().apply{setIntent(intent(a));onCreate(null)}
        val home=ProjectedHomeActivity::class.java.getDeclaredField("home").apply{isAccessible=true}.get(activity) as ProjectedHomeView
        val oldCallback=home.onAppSelected!!
        val entered=CountDownLatch(1);val release=CountDownLatch(1)
        (field("displayExecutor")as ExecutorService).submit{entered.countDown();check(release.await(4,TimeUnit.SECONDS))}
        check(entered.await(2,TimeUnit.SECONDS))
        try {
            oldCallback(ChosenApp("example.old.home","Old Home"))
            service.sessionEpoch="home-b";service.display=service.display.copy(context=ProjectionContext("","home-token-b"),displayId=88)
            host.refresh();lifecycle()
            val b=host.status().projectionContext
            activity.onNewIntent(intent(b))
            val freshCallback=home.onAppSelected!!
            val previous=home.statuses.toList()
            oldCallback(ChosenApp("example.delayed.old.home","Delayed Old Home"))
            release.countDown();drain()
            check(service.launches.isEmpty() && home.statuses==previous)
            freshCallback(ChosenApp("example.fresh.home","Fresh Home"));drain()
            check(service.launches==listOf("example.fresh.home") && service.display.context==b)
            activity.onNewIntent(Intent())
            home.onAppSelected!!(ChosenApp("example.missing.home.context","Missing"));drain()
            check(service.launches==listOf("example.fresh.home"))
        }finally{release.countDown()}
    }
}
