package zio.bdd.lsp

import org.eclipse.lsp4j.launch.LSPLauncher
import zio.*

// Import java.lang.System members directly to avoid zio.System shadowing
import java.lang.System as JSystem

object ZIOBddLanguageServer:
  def start(): Task[Unit] =
    ZIO.scoped {
      for
        index  <- WorkspaceIndex.layer.build.map(_.get)
        server <- ZIOBddServer.layer.build.provideSome[Scope](ZLayer.succeed(index)).map(_.get)
        _ <- ZIO.attemptBlocking {
               val launcher = LSPLauncher.createServerLauncher(
                 server,
                 JSystem.in,
                 JSystem.out
               )
               server.setClient(launcher.getRemoteProxy)
               val future = launcher.startListening()
               future.get()
             }
      yield ()
    }
