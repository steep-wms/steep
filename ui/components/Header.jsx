import Head from "next/head"
import NavBar from "./NavBar"
import "./Header.scss"

export default ({ title = "Steep Workflow Management System" }) => (
  <header>
    <Head>
      <meta httpEquiv="X-UA-Compatible" content="IE=edge"/>
      <meta name="viewport" content="width=device-width, initial-scale=1, shrink-to-fit=no"/>
      <meta name="author" content="Michel Krämer"/>
      <meta name="description" content="Scientific Workflows in the Cloud"/>
      <meta name="robots" content="index,follow"/>
      <link href="https://fonts.googleapis.com/css?family=Roboto:300,400" rel="stylesheet"/>
      <link href="https://fonts.googleapis.com/css?family=Roboto+Condensed:300,400" rel="stylesheet"/>
      <title>{title}</title>
    </Head>
    <NavBar />
  </header>
)
